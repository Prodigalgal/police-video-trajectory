package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Internal, bounded, audited recovery. No bulk reset or completed-result overwrite. */
@Service
public class PipelineOperations {
    public record Recovery(UUID requestId,long expectedRevision,String actor,String reason) {}
    public static class Rejected extends RuntimeException {
        public final int status;
        public final String code;
        public Rejected(int status,String code) { super("pipeline operation rejected");this.status=status;this.code=code; }
    }
    private final JdbcTemplate db;
    private final AnalysisQueue queue;
    private final CaptureEvidence evidence;
    private final Path evidenceRoot;
    private final ObjectMapper json=new ObjectMapper();
    public PipelineOperations(JdbcTemplate db,AnalysisQueue queue,CaptureEvidence evidence,
                              @Value("${VIDEO_EVIDENCE_DIR:/evidence}") String root) {
        this.db=db;this.queue=queue;this.evidence=evidence;this.evidenceRoot=Path.of(root);
    }

    static void validate(Recovery request) {
        if(request==null || request.requestId()==null || request.expectedRevision()<0 || request.actor()==null ||
                !request.actor().matches("[A-Za-z0-9_.-]{1,100}") || request.reason()==null ||
                request.reason().isBlank() || request.reason().length()>300 || request.reason().chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid recovery request");
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> replay(String operation,UUID target,Recovery request) {
        var rows=db.queryForList("SELECT * FROM pipeline_recovery_requests WHERE request_id=?",request.requestId());
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!operation.equals(row.get("operation")) || !target.equals(row.get("target_id")) ||
                ((Number)row.get("expected_revision")).longValue()!=request.expectedRevision() ||
                !request.actor().equals(row.get("actor_id")) || !request.reason().equals(row.get("reason")))
            throw new Rejected(409,"idempotency_conflict");
        var result=new LinkedHashMap<String,Object>(json.readValue(row.get("receipt").toString(),Map.class));
        result.put("replayed",true);
        return result;
    }
    private Map<String,Object> record(String operation,UUID target,Recovery request,Map<String,Object> prior,Map<String,Object> receipt) {
        receipt=new LinkedHashMap<>(receipt);
        receipt.put("request_id",request.requestId());receipt.put("operation",operation);receipt.put("replayed",false);
        db.update("""
                INSERT INTO pipeline_recovery_requests(request_id,operation,target_id,expected_revision,actor_id,reason,prior_state,receipt)
                VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """,request.requestId(),operation,target,request.expectedRevision(),request.actor(),request.reason(),
                json.writeValueAsString(prior),json.writeValueAsString(receipt));
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",
                request.actor(),"pipeline."+operation,"applied",request.requestId());
        return receipt;
    }

    @Transactional
    public Map<String,Object> retry(UUID jobId,Recovery request) {
        validate(request);
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var replay=replay("retry_analysis",jobId,request);
        if(replay!=null) return replay;
        var rows=db.queryForList("""
                SELECT j.job_id,j.frame_id,j.state,j.attempts,j.lease_generation,j.last_error_code,
                  f.analysis_state,c.payload_sha256 FROM analysis_jobs j JOIN frames f USING(frame_id)
                LEFT JOIN capture_outbox c USING(frame_id) WHERE j.job_id=? FOR UPDATE OF j
                """,jobId);
        if(rows.isEmpty()) throw new Rejected(404,"job_not_found");
        var row=rows.getFirst();
        if(!"failed".equals(row.get("state")) || !"failed".equals(row.get("analysis_state")) ||
                ((Number)row.get("lease_generation")).longValue()!=request.expectedRevision())
            throw new Rejected(409,"state_revision_conflict");
        UUID frame=(UUID)row.get("frame_id");
        if(db.queryForObject("SELECT count(*) FROM observations WHERE frame_id=?",Long.class,frame)>0 ||
                db.queryForObject("SELECT count(*) FROM analysis_modules WHERE frame_id=? AND result IS NOT NULL",Long.class,frame)>0)
            throw new Rejected(409,"result_already_present");
        evidence.verify(frame,(String)row.get("payload_sha256"));
        long pending=db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE state IN('queued','active')",Long.class);
        if(pending>=queue.pendingLimit()) throw new Rejected(429,"analysis_queue_full");
        long generation=request.expectedRevision()+1;
        db.update("""
                UPDATE analysis_jobs SET state='queued',attempts=0,lease_owner=NULL,lease_generation=?,lease_expires_at=NULL,
                  next_attempt_at=clock_timestamp(),last_error_code=NULL,updated_at=clock_timestamp() WHERE job_id=?
                """,generation,jobId);
        db.update("UPDATE frames SET analysis_state='queued' WHERE frame_id=?",frame);
        return record("retry_analysis",jobId,request,row,Map.of("job_id",jobId,"applied_state","queued","generation",generation));
    }

    @Transactional
    public Map<String,Object> restore(UUID frameId,Recovery request) {
        validate(request);
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var replay=replay("restore_capture",frameId,request);
        if(replay!=null) return replay;
        var rows=db.queryForList("""
                SELECT q.frame_id,q.revision,q.error_code,q.restored_at,c.payload_sha256 FROM capture_faults q
                JOIN capture_outbox c USING(frame_id) WHERE q.frame_id=? FOR UPDATE OF q
                """,frameId);
        if(rows.isEmpty()) throw new Rejected(404,"capture_fault_not_found");
        var row=rows.getFirst();
        if(row.get("restored_at")!=null || ((Number)row.get("revision")).longValue()!=request.expectedRevision())
            throw new Rejected(409,"state_revision_conflict");
        evidence.verify(frameId,(String)row.get("payload_sha256"));
        db.update("UPDATE capture_faults SET restored_at=clock_timestamp(),revision=revision+1 WHERE frame_id=?",frameId);
        return record("restore_capture",frameId,request,row,Map.of("frame_id",frameId,"applied_state","waiting",
                "revision",request.expectedRevision()+1));
    }

    public Map<String,Object> jobs(String state,String camera,int limit,UUID after) {
        if(!Set.of("failed","queued","active","completed").contains(state) || limit<1 || limit>100 ||
                (camera!=null && !camera.matches("[A-Za-z0-9_-]{1,100}"))) throw new IllegalArgumentException("invalid page");
        var rows=db.queryForList("""
                SELECT j.job_id,j.frame_id,s.camera_id,j.state,j.attempts,j.lease_generation AS revision,j.last_error_code,
                  j.next_attempt_at,j.updated_at,j.lease_expires_at,(c.frame_id IS NOT NULL) AS target_aware
                FROM analysis_jobs j JOIN frames f USING(frame_id) JOIN processing_tasks t ON t.task_id=f.task_id
                JOIN sources s USING(source_id) LEFT JOIN capture_outbox c ON c.frame_id=j.frame_id
                WHERE j.state=? AND (?::text IS NULL OR s.camera_id=?) AND (?::uuid IS NULL OR j.job_id>?)
                ORDER BY j.job_id LIMIT ?
                """,state,camera,camera,after,after,limit+1);
        return page(rows,limit,"job_id");
    }
    public Map<String,Object> quarantined(int limit,UUID after) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("invalid page");
        return page(db.queryForList("""
                SELECT q.frame_id,c.camera_id,q.error_code,q.revision,q.detected_at FROM capture_faults q
                JOIN capture_outbox c USING(frame_id) WHERE q.restored_at IS NULL AND (?::uuid IS NULL OR q.frame_id>?)
                ORDER BY q.frame_id LIMIT ?
                """,after,after,limit+1),limit,"frame_id");
    }
    private Map<String,Object> page(List<Map<String,Object>> rows,int limit,String identity) {
        boolean more=rows.size()>limit;
        var items=rows.subList(0,Math.min(limit,rows.size()));
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("has_more",more);
        result.put("next_cursor",more?items.getLast().get(identity):null);
        result.put("pagination","UUID keyset; moving state, not a consistent export");return result;
    }

    public Map<String,Object> health() {
        var result=new LinkedHashMap<String,Object>();
        result.put("observed_at",Instant.now().toString());
        result.put("claims_enabled",db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));
        result.put("analysis_capacity",queue.pendingLimit());
        result.put("analysis_policy",queue.policy());
        result.put("analysis_load",queue.load());
        result.put("analysis",db.queryForList("SELECT state,count(*) AS count,min(created_at) AS oldest_created_at FROM analysis_jobs GROUP BY state ORDER BY state"));
        result.put("analysis_last_failures",db.queryForList("SELECT state,last_error_code,count(*) AS count FROM analysis_jobs WHERE last_error_code IS NOT NULL GROUP BY state,last_error_code ORDER BY state,last_error_code"));
        result.put("waiting_captures",db.queryForObject("SELECT count(*) FROM capture_outbox c WHERE enqueued_at IS NULL AND NOT EXISTS(SELECT 1 FROM capture_faults q WHERE q.frame_id=c.frame_id AND q.restored_at IS NULL)",Long.class));
        result.put("quarantined_captures",db.queryForObject("SELECT count(*) FROM capture_faults WHERE restored_at IS NULL",Long.class));
        result.put("completed_last_5m",db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE state='completed' AND updated_at>clock_timestamp()-interval '5 minutes'",Long.class));
        result.put("pending_index_events",db.queryForObject("SELECT count(*) FROM index_outbox WHERE completed_at IS NULL",Long.class));
        var sources=db.queryForList("SELECT s.camera_id,s.enabled,t.state,t.lease_expires_at FROM sources s JOIN processing_tasks t USING(source_id) WHERE s.source_type='rtsp' ORDER BY s.camera_id");
        for(var source:sources) source.put("capture_diagnostics",captureDiagnostics((String)source.get("camera_id"),Boolean.TRUE.equals(source.get("enabled")) && "active".equals(source.get("state"))));
        result.put("sources",sources);result.put("coverage","unknown; sampled counts do not prove complete video coverage");
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> captureDiagnostics(String camera,boolean active) {
        var result=new LinkedHashMap<String,Object>();
        result.put("scope",active?"unknown":"retained_previous_reader");
        if(!camera.matches("[A-Za-z0-9_-]{1,100}")) return result;
        Path file=evidenceRoot.resolve(".scene-state/rtsp").resolve(camera+".json");
        try {
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>128*1024) return result;
            byte[] bytes;
            try(var input=Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(128*1024+1); }
            if(bytes.length>128*1024) return result;
            var state=json.readValue(bytes,Map.class);
            if(!camera.equals(state.get("camera_id"))) return result;
            Instant observed=Instant.parse((String)state.get("observed_at"));
            long age=Duration.between(observed,Instant.now()).getSeconds();
            result.put("snapshot_age_seconds",Math.max(0,age));
            result.put("scope",active?(age>=-5 && age<=30?"current_reader":"stale"):"retained_previous_reader");
            for(String key:List.of("run_id","durable_frames","durable_spool_gaps","output_overflow_gaps","corrupt_frames",
                    "audit_samples_saved","audit_samples_failed","audit_sampling_gaps","activity_unknown_observations","activity_incomplete_region_maps"))
                if(state.containsKey(key)) result.put(key,state.get(key));
            result.put("counter_period","per reader run; do not sum retained and current runs");
        } catch(Exception unavailable) { result.put("scope",active?"unavailable":"retained_previous_reader"); }
        return result;
    }
}
