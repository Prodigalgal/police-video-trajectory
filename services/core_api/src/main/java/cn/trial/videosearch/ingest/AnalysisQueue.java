package cn.trial.videosearch.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable bounded queue with fenced leases and a shared consumer quota. */
@Service
public class AnalysisQueue {
    private static final Set<String> FAILURE_CODES = Set.of("model_unavailable", "model_timeout", "model_rate_limited",
            "job_timeout", "consumer_interrupted", "invalid_model_result", "evidence_unavailable", "result_commit_unavailable");
    private final JdbcTemplate db;
    private final int pendingLimit;
    private final AnalysisQueuePolicy policy;
    public record Submission(UUID frameId, UUID taskId, String workerId, long generation, String evidenceRef,
                             Long mediaOffsetMs, Instant sourceTime) {}
    public record Job(UUID jobId, UUID frameId, String evidenceRef, int attempts, long generation, Instant expiresAt) {}

    public AnalysisQueue(JdbcTemplate db, @Value("${VIDEO_ANALYSIS_QUEUE_LIMIT:500}") int pendingLimit, AnalysisQueuePolicy policy) {
        if (pendingLimit < 1 || pendingLimit > 10000) throw new IllegalArgumentException("invalid analysis queue limit");
        this.db = db;
        this.pendingLimit = pendingLimit;
        this.policy = java.util.Objects.requireNonNull(policy);
    }

    public int pendingLimit() { return pendingLimit; }
    public Map<String, Integer> policy() { return policy.snapshot(pendingLimit); }

    public Map<String, Object> load() {
        return db.queryForMap("""
                SELECT count(*) FILTER(WHERE state IN ('queued','active')) AS pending,
                  count(*) FILTER(WHERE state='queued' AND next_attempt_at<=clock_timestamp()) AS ready,
                  count(*) FILTER(WHERE state='queued' AND next_attempt_at>clock_timestamp()) AS retry_waiting,
                  count(*) FILTER(WHERE state='active' AND lease_expires_at>clock_timestamp()) AS active,
                  count(*) FILTER(WHERE state='active' AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp())) AS expired,
                  count(*) FILTER(WHERE state='failed') AS failed,
                  count(*) FILTER(WHERE state IN ('queued','active'))>=? AS producer_blocked,
                  count(*) FILTER(WHERE state='active' AND lease_expires_at>clock_timestamp())>=? AS consumer_saturated
                FROM analysis_jobs
                """, pendingLimit, Math.min(pendingLimit, policy.activeLimit()));
    }

    @Transactional
    public UUID enqueue(Submission item) {
        if (item == null || item.frameId() == null || item.taskId() == null || !owner(item.workerId()) || item.generation() < 1 ||
                item.evidenceRef() == null || !item.evidenceRef().matches("evidence:[A-Za-z0-9_-]{1,100}") ||
                (item.mediaOffsetMs() != null && item.mediaOffsetMs() < 0)) throw new IllegalArgumentException("invalid frame submission");
        var leases = db.queryForList("""
                SELECT s.source_time_origin FROM processing_tasks t JOIN sources s ON s.source_id=t.source_id
                WHERE t.task_id=? AND t.state='active' AND t.lease_owner=? AND t.lease_generation=?
                  AND t.lease_expires_at>clock_timestamp() AND s.enabled FOR UPDATE OF t
                """, item.taskId(), item.workerId(), item.generation());
        if (leases.size() != 1) throw new IllegalStateException("source lease expired or replaced");
        if (!Integer.valueOf(0).equals(db.queryForObject("SELECT count(*) FROM capture_outbox WHERE frame_id=?", Integer.class, item.frameId())))
            throw new IllegalStateException("registered capture requires its receipt");
        String origin = (String) leases.getFirst().get("source_time_origin");
        if (!origin.equals("verified") && item.sourceTime() != null) throw new IllegalArgumentException("source clock is unverified");
        Timestamp sourceTime = item.sourceTime() == null ? null : Timestamp.from(item.sourceTime());
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var existing = db.queryForList("SELECT frame_id FROM frames WHERE frame_id=?", item.frameId());
        if (existing.isEmpty()) {
            Integer pending = db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE state IN ('queued','active')", Integer.class);
            if (pending == null || pending >= pendingLimit) throw new IllegalStateException("analysis queue is full");
            db.update("""
                    INSERT INTO frames(frame_id,task_id,lease_generation,evidence_ref,media_offset_ms,source_time,source_time_origin,received_at,analysis_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), 'queued')
                    """, item.frameId(), item.taskId(), item.generation(), item.evidenceRef(), item.mediaOffsetMs(), sourceTime, origin);
            UUID jobId = UUID.nameUUIDFromBytes(("analysis:" + item.frameId()).getBytes(StandardCharsets.UTF_8));
            db.update("INSERT INTO analysis_jobs(job_id,frame_id,state) VALUES (?, ?, 'queued')", jobId, item.frameId());
        } else {
            Integer same = db.queryForObject("""
                    SELECT count(*) FROM frames f JOIN analysis_jobs j ON j.frame_id=f.frame_id
                    WHERE f.frame_id=? AND f.task_id=? AND f.evidence_ref=?
                      AND f.media_offset_ms IS NOT DISTINCT FROM ? AND f.source_time IS NOT DISTINCT FROM ? AND f.source_time_origin=?
                    """, Integer.class, item.frameId(), item.taskId(), item.evidenceRef(), item.mediaOffsetMs(), sourceTime, origin);
            if (same == null || same != 1) throw new IllegalStateException("conflicting frame replay");
        }
        int live = db.update("""
                UPDATE processing_tasks SET updated_at=clock_timestamp() WHERE task_id=? AND state='active' AND lease_owner=?
                  AND lease_generation=? AND lease_expires_at>clock_timestamp()
                """, item.taskId(), item.workerId(), item.generation());
        if (live != 1) throw new IllegalStateException("source lease expired before commit");
        return db.queryForObject("SELECT job_id FROM analysis_jobs WHERE frame_id=?", UUID.class, item.frameId());
    }

    @Transactional
    public java.util.Optional<Job> claim(String workerId, int seconds) {
        return claimCompatible(workerId, seconds, false);
    }

    @Transactional
    public java.util.Optional<Job> claimTargets(String workerId, int seconds) {
        return claimCompatible(workerId, seconds, true);
    }

    private java.util.Optional<Job> claimCompatible(String workerId, int seconds, boolean targetAware) {
        validateLease(workerId, seconds);
        // Capacity is checked and consumed under the same short transaction lock
        // as enqueue/recovery. Pod-local slot limits cannot enforce this quota.
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'", Boolean.class)))
            return java.util.Optional.empty();
        recoverExpired();
        Long active = db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE state='active' AND lease_expires_at>clock_timestamp()", Long.class);
        if (active == null || active >= Math.min(pendingLimit, policy.activeLimit())) return java.util.Optional.empty();
        var rows = db.query("""
                WITH candidate AS (
                  SELECT job_id FROM analysis_jobs WHERE attempts<? AND
                    (? OR NOT EXISTS(SELECT 1 FROM capture_outbox c WHERE c.frame_id=analysis_jobs.frame_id)) AND
                    state='queued' AND next_attempt_at<=clock_timestamp()
                  ORDER BY CASE WHEN ? AND EXISTS(SELECT 1 FROM capture_outbox c WHERE c.frame_id=analysis_jobs.frame_id) THEN 0 ELSE 1 END,
                    next_attempt_at,created_at,job_id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE analysis_jobs j SET state='active',lease_owner=?,lease_generation=j.lease_generation+1,
                  lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                FROM candidate,frames f WHERE j.job_id=candidate.job_id AND f.frame_id=j.frame_id
                RETURNING j.job_id,j.frame_id,f.evidence_ref,j.attempts,j.lease_generation,j.lease_expires_at
                """, (rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getInt(4), rs.getLong(5), rs.getTimestamp(6).toInstant()), policy.maxAttempts(), targetAware, targetAware, workerId, seconds);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.getFirst());
    }

    private void recoverExpired() {
        db.update("""
                WITH exhausted AS (
                  UPDATE analysis_jobs SET state='failed',lease_owner=NULL,lease_expires_at=NULL,
                    last_error_code=COALESCE(last_error_code,'retry_exhausted'),updated_at=clock_timestamp()
                  WHERE state='queued' AND attempts>=? RETURNING frame_id
                ) UPDATE frames SET analysis_state='failed' WHERE frame_id IN (SELECT frame_id FROM exhausted)
                """, policy.maxAttempts());
        db.update("""
                WITH expired AS (
                  UPDATE analysis_jobs SET state=CASE WHEN attempts+1>=? THEN 'failed' ELSE 'queued' END,
                    attempts=attempts+1,last_error_code='lease_expired',lease_owner=NULL,lease_expires_at=NULL,
                    next_attempt_at=clock_timestamp()+(LEAST(?,? * power(2,LEAST(attempts,20))) * interval '1 second'),
                    updated_at=clock_timestamp()
                  WHERE state='active' AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp()) RETURNING frame_id,state
                )
                UPDATE frames f SET analysis_state=CASE WHEN e.state='failed' THEN 'failed' ELSE 'queued' END
                FROM expired e WHERE f.frame_id=e.frame_id
                """, policy.maxAttempts(), policy.retryMaxSeconds(), policy.retryBaseSeconds());
    }

    public boolean renew(UUID jobId, String workerId, long generation, int seconds) {
        validateLease(workerId, seconds);
        if (jobId == null || generation < 1) throw new IllegalArgumentException("invalid analysis lease");
        return db.update("""
                UPDATE analysis_jobs SET lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
                """, seconds, jobId, workerId, generation) == 1;
    }

    @Transactional
    public boolean fail(UUID jobId, String workerId, long generation, String errorCode) {
        if (jobId == null || !owner(workerId) || generation < 1 || errorCode == null || !FAILURE_CODES.contains(errorCode))
            throw new IllegalArgumentException("invalid analysis failure");
        var rows = db.queryForList("""
                SELECT attempts,frame_id FROM analysis_jobs WHERE job_id=? AND state='active' AND lease_owner=?
                  AND lease_generation=? AND lease_expires_at>clock_timestamp() FOR UPDATE
                """, jobId, workerId, generation);
        if (rows.isEmpty()) return false;
        int attempts = ((Number) rows.getFirst().get("attempts")).intValue() + 1;
        boolean terminal = attempts >= policy.maxAttempts();
        int delay = policy.retryDelaySeconds(attempts);
        db.update("""
                UPDATE analysis_jobs SET state=?,attempts=?,last_error_code=?,lease_owner=NULL,lease_expires_at=NULL,
                  next_attempt_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=?
                """, terminal ? "failed" : "queued", attempts, errorCode, delay, jobId);
        if (terminal) db.update("UPDATE frames SET analysis_state='failed' WHERE frame_id=?", rows.getFirst().get("frame_id"));
        return true;
    }

    private static boolean owner(String owner) { return owner != null && owner.matches("[A-Za-z0-9_.-]{1,100}"); }
    private static void validateLease(String owner, int seconds) {
        if (!owner(owner) || seconds < 1 || seconds > 600) throw new IllegalArgumentException("invalid analysis lease");
    }
}
