package cn.trial.videosearch.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import cn.trial.videosearch.ingestion.IngestionPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Database time and generation prevent an old worker from taking over a new lease. */
@Service
public class TaskLeaseService {
    private final JdbcTemplate db;
    private final IngestionPolicy policy;
    private final TransactionTemplate transaction;
    public record Lease(long generation, Instant expiresAt) {}
    public record Claim(UUID taskId, UUID sourceId, String cameraId, String sourceType, String sourceRef,
                        String sourceTimeOrigin, long generation, Instant expiresAt) {}

    public TaskLeaseService(JdbcTemplate db,IngestionPolicy policy,PlatformTransactionManager manager) {this.db=db;this.policy=policy;transaction=new TransactionTemplate(manager);transaction.setTimeout(10);}

    public Optional<Lease> acquire(UUID task, String owner, int seconds) {
        validate(task, owner, seconds);
        if(policy.enabled())return transaction.execute(status->{policy.lock(db);var rows=db.queryForList("SELECT task_type FROM processing_tasks WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM ingestion_task_specs p WHERE p.task_id=processing_tasks.task_id)",task);if(rows.isEmpty()||!policy.available(db,(String)rows.getFirst().get("task_type")))return Optional.empty();return acquireLegacy(task,owner,seconds,true);});
        return acquireLegacy(task,owner,seconds,policy.managedSchemaPresent(db));
    }
    private Optional<Lease> acquireLegacy(UUID task,String owner,int seconds,boolean excludeManaged){
        var sql = """
                UPDATE processing_tasks SET state='active', lease_owner=?, lease_generation=lease_generation+1,
                    lease_expires_at=clock_timestamp()+(? * interval '1 second'), updated_at=clock_timestamp()
                WHERE task_id=? AND state IN ('queued','active')
                  AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
                  %s
                  %s
                RETURNING lease_generation, lease_expires_at
                """.formatted(excludeManaged?"AND NOT EXISTS(SELECT 1 FROM ingestion_task_specs p WHERE p.task_id=processing_tasks.task_id)":"",policy.enabled()?policy.liveSourceGuard("processing_tasks"):"");
        return one(db.query(sql, (rs, n) -> new Lease(rs.getLong(1), rs.getTimestamp(2).toInstant()), owner, seconds, task));
    }

    public Optional<Claim> claim(String owner, String type, int seconds) {
        validate(UUID.randomUUID(), owner, seconds);
        if (!List.of("rtsp", "archive").contains(type)) throw new IllegalArgumentException("invalid task type");
        if(policy.enabled())return transaction.execute(status->{policy.lock(db);return policy.available(db,type)?claimLegacy(owner,type,seconds,true):Optional.empty();});
        return claimLegacy(owner,type,seconds,policy.managedSchemaPresent(db));
    }
    private Optional<Claim> claimLegacy(String owner,String type,int seconds,boolean excludeManaged){
        var sql = """
                WITH candidate AS (
                    SELECT t.task_id FROM processing_tasks t JOIN sources s ON s.source_id=t.source_id
                    JOIN cameras c ON c.camera_id=s.camera_id
                    WHERE t.task_type=? AND t.state IN ('queued','active') AND s.enabled AND c.enabled
                      %s
                      %s
                      AND (t.lease_expires_at IS NULL OR t.lease_expires_at<=clock_timestamp())
                    ORDER BY t.updated_at, t.task_id FOR UPDATE OF t SKIP LOCKED LIMIT 1
                )
                UPDATE processing_tasks t SET state='active', lease_owner=?, lease_generation=t.lease_generation+1,
                    lease_expires_at=clock_timestamp()+(? * interval '1 second'), updated_at=clock_timestamp()
                FROM candidate, sources s WHERE t.task_id=candidate.task_id AND s.source_id=t.source_id
                RETURNING t.task_id,t.source_id,s.camera_id,s.source_type,s.source_ref,s.source_time_origin,
                          t.lease_generation,t.lease_expires_at
                """.formatted(excludeManaged?"AND NOT EXISTS(SELECT 1 FROM ingestion_task_specs p WHERE p.task_id=t.task_id)":"",policy.enabled()?policy.liveSourceGuard("t"):"");
        var rows = db.query(sql, (rs, n) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getTimestamp(8).toInstant()),
                type, owner, seconds);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Lease> renew(UUID task, String owner, long generation, int seconds) {
        validate(task, owner, seconds);
        if (generation <= 0) throw new IllegalArgumentException("invalid generation");
        var sql = """
                UPDATE processing_tasks SET lease_expires_at=clock_timestamp()+(? * interval '1 second'), updated_at=clock_timestamp()
                WHERE task_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
                  %s
                RETURNING lease_generation, lease_expires_at
                """.formatted(legacyBoundary());
        return one(db.query(sql, (rs, n) -> new Lease(rs.getLong(1), rs.getTimestamp(2).toInstant()), seconds, task, owner, generation));
    }

    public boolean finish(UUID task, String owner, long generation, boolean completed) {
        validate(task, owner, 1);
        if (generation <= 0) throw new IllegalArgumentException("invalid generation");
        return db.update("""
                UPDATE processing_tasks SET state=?, lease_owner=NULL, lease_expires_at=NULL, updated_at=clock_timestamp()
                WHERE task_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
                  %s
                """.formatted(legacyBoundary()), completed ? "completed" : "queued", task, owner, generation) == 1;
    }

    private String legacyBoundary(){return policy.enabled()||policy.managedSchemaPresent(db)?"AND NOT EXISTS(SELECT 1 FROM ingestion_task_specs p WHERE p.task_id=processing_tasks.task_id)":"";}

    private static Optional<Lease> one(List<Lease> rows) { return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst()); }
    private static void validate(UUID task, String owner, int seconds) {
        if (task == null || owner == null || !owner.matches("[A-Za-z0-9_.-]{1,100}") || seconds < 1 || seconds > 600)
            throw new IllegalArgumentException("invalid lease request");
    }
}
