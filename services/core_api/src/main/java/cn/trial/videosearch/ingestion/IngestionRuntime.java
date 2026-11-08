package cn.trial.videosearch.ingestion;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;

/** Managed source leases and safe read cursors; no inference or image I/O in transactions. */
@Service
public class IngestionRuntime {
    public record Claim(String workerId,String sourceType,int leaseSeconds,List<String> contracts){}
    public record Lease(String workerId,long generation,int leaseSeconds){}
    public record Finish(String workerId,long generation,boolean completed,String errorCode){}
    public record Progress(UUID checkpointId,String workerId,long generation,String inputSha256,long observedThroughMs,Long safeThroughMs,UUID lastFrameId,boolean readerFinished,Map<String,Long> gaps){}
    public record ReceiptLookup(String workerId,long generation,String inputSha256,List<UUID> frameIds){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FAILURES=Set.of("source_decode_error","source_file_changed","source_input_invalid","source_checkpoint_gap","source_worker_error","source_checkpoint_unavailable","source_interrupted","source_replay_window_full");
    private final JdbcTemplate db;
    private final IngestionPolicy policy;
    private final TransactionTemplate tx;
    public IngestionRuntime(JdbcTemplate db,IngestionPolicy policy,PlatformTransactionManager manager){this.db=db;this.policy=policy;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    private void enabled(){if(!policy.enabled())throw new AdminService.Rejected(404,"ingestion_disabled");}
    private static void worker(String worker,long generation,int seconds){if(worker==null||!worker.matches("[A-Za-z0-9_.-]{1,100}")||generation<1||seconds<1||seconds>600)throw new IllegalArgumentException("Invalid source lease");}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(String json){return JSON.readValue(json,Map.class);}
    private void expire(){
        db.update("""
            WITH expired AS (
              UPDATE ingestion_task_specs p SET failures=p.failures+1,revision=p.revision+1,last_error_code='source_lease_expired',
                next_attempt_at=clock_timestamp()+interval '5 seconds'
              FROM processing_tasks t WHERE t.task_id=p.task_id AND t.state='active' AND t.lease_expires_at<=clock_timestamp()
              RETURNING p.task_id,p.failures)
            UPDATE processing_tasks t SET state=CASE WHEN e.failures>=? THEN 'failed' ELSE 'queued' END,
              lease_owner=NULL,lease_expires_at=NULL,last_error_code='source_lease_expired',updated_at=clock_timestamp()
            FROM expired e WHERE t.task_id=e.task_id
            """,policy.maxFailures());
    }
    public Map<String,Object> claim(Claim body){
        enabled();if(body==null||body.sourceType()==null||!Set.of("rtsp","archive").contains(body.sourceType())||body.contracts()==null||!body.contracts().contains("managed-ingestion-v1")||body.contracts().size()>4)throw new IllegalArgumentException();worker(body.workerId(),1,body.leaseSeconds());
        return tx.execute(status->{policy.lock(db);expire();if(!policy.available(db,body.sourceType()))return Map.of("task_available",false);
            var rows=db.queryForList("""
                SELECT t.task_id,t.source_id,s.camera_id,s.source_type,s.source_ref,s.source_time_origin,
                  p.source_snapshot::text AS snapshot,p.input_sha256,p.start_ms,p.end_ms,p.safe_through_ms,p.observed_through_ms,p.priority
                FROM processing_tasks t JOIN ingestion_task_specs p USING(task_id) JOIN sources s ON s.source_id=t.source_id
                JOIN cameras c ON c.camera_id=s.camera_id
                WHERE t.task_type=? AND t.state='queued' AND NOT p.paused AND s.enabled AND c.enabled
                  %s
                  AND p.next_attempt_at<=clock_timestamp() ORDER BY p.priority DESC,p.next_attempt_at,t.task_id
                FOR UPDATE OF t,p SKIP LOCKED LIMIT 1
                """.formatted(policy.liveSourceGuard("t")),body.sourceType());
            if(rows.isEmpty())return Map.of("task_available",false);var row=rows.getFirst();var snapshot=object((String)row.get("snapshot"));
            for(String key:List.of("source_id","camera_id","source_type","source_ref","source_time_origin")){
                if(!Objects.equals(String.valueOf(snapshot.get(key)),String.valueOf(row.get(key)))){
                    db.update("UPDATE processing_tasks SET state='failed',last_error_code='source_input_changed',updated_at=clock_timestamp() WHERE task_id=?",row.get("task_id"));
                    db.update("UPDATE ingestion_task_specs SET revision=revision+1,last_error_code='source_input_changed' WHERE task_id=?",row.get("task_id"));return Map.of("task_available",false);
                }
            }
            var lease=db.queryForList("UPDATE processing_tasks SET state='active',lease_owner=?,lease_generation=lease_generation+1,lease_expires_at=clock_timestamp()+(?*interval '1 second'),updated_at=clock_timestamp() WHERE task_id=? RETURNING lease_generation,lease_expires_at",body.workerId(),body.leaseSeconds(),row.get("task_id")).getFirst();
            db.update("UPDATE ingestion_task_specs SET revision=revision+1,reader_finished=false WHERE task_id=?",row.get("task_id"));
            var task=new LinkedHashMap<String,Object>();
            for(String[] names:new String[][]{{"task_id","taskId"},{"source_id","sourceId"},{"camera_id","cameraId"},{"source_type","sourceType"},{"source_ref","sourceRef"},{"source_time_origin","sourceTimeOrigin"}})task.put(names[1],String.valueOf(row.get(names[0])));
            task.put("generation",lease.get("lease_generation"));task.put("expiresAt",((Timestamp)lease.get("lease_expires_at")).toInstant().toString());
            var managed=new LinkedHashMap<String,Object>();managed.put("contract","managed-ingestion-v1");managed.put("input_sha256",row.get("input_sha256"));managed.put("source",snapshot);managed.put("start_ms",row.get("start_ms"));managed.put("end_ms",row.get("end_ms"));managed.put("safe_through_ms",row.get("safe_through_ms"));managed.put("observed_through_ms",row.get("observed_through_ms"));task.put("managed",managed);
            return Map.of("task",task);
        });
    }
    private Map<String,Object> owned(UUID task,String owner,long generation){
        var rows=db.queryForList("""
            SELECT t.*,p.start_ms,p.end_ms,p.safe_through_ms,p.observed_through_ms,p.input_sha256,p.failures,p.reader_finished
            FROM processing_tasks t JOIN ingestion_task_specs p USING(task_id) JOIN sources s ON s.source_id=t.source_id JOIN cameras c ON c.camera_id=s.camera_id
            WHERE t.task_id=? AND t.state='active' AND t.lease_owner=? AND t.lease_generation=? AND t.lease_expires_at>clock_timestamp()
              AND NOT p.paused AND s.enabled AND c.enabled FOR UPDATE OF t,p
            """,task,owner,generation);
        if(rows.size()!=1)throw new AdminService.Rejected(409,"ingestion_lease_expired");return rows.getFirst();
    }
    public Map<String,Object> renew(UUID task,Lease body){enabled();if(body==null)throw new IllegalArgumentException();worker(body.workerId(),body.generation(),body.leaseSeconds());return tx.execute(status->{policy.lock(db);owned(task,body.workerId(),body.generation());var row=db.queryForList("UPDATE processing_tasks SET lease_expires_at=clock_timestamp()+(?*interval '1 second'),updated_at=clock_timestamp() WHERE task_id=? RETURNING lease_generation,lease_expires_at",body.leaseSeconds(),task).getFirst();return Map.of("lease",Map.of("generation",row.get("lease_generation"),"expiresAt",((Timestamp)row.get("lease_expires_at")).toInstant().toString()));});}
    public Map<String,Object> finish(UUID task,Finish body){
        enabled();if(body==null)throw new IllegalArgumentException();worker(body.workerId(),body.generation(),1);String error=body.errorCode()==null?"source_interrupted":body.errorCode();if(!FAILURES.contains(error))throw new IllegalArgumentException("Unknown source failure");
        return tx.execute(status->{policy.lock(db);var row=owned(task,body.workerId(),body.generation());
            if(body.completed()&&!Boolean.TRUE.equals(row.get("reader_finished")))throw new AdminService.Rejected(409,"source_checkpoint_incomplete");
            int failures=((Number)row.get("failures")).intValue()+(body.completed()?0:1);String state=body.completed()?"completed":failures>=policy.maxFailures()?"failed":"queued";
            db.update("UPDATE processing_tasks SET state=?,lease_owner=NULL,lease_expires_at=NULL,last_error_code=?,updated_at=clock_timestamp() WHERE task_id=?",state,body.completed()?null:error,task);
            db.update("UPDATE ingestion_task_specs SET failures=?,revision=revision+1,last_error_code=?,next_attempt_at=clock_timestamp()+(?*interval '1 second') WHERE task_id=?",failures,body.completed()?null:error,body.completed()?0:policy.retrySeconds(failures),task);
            return Map.of("finished",true,"state",state);
        });
    }
    public Map<String,Object> captures(UUID task,String owner,long generation,String sha){
        enabled();worker(owner,generation,1);return tx.execute(status->{policy.lock(db);var row=owned(task,owner,generation);if(!Objects.equals(sha,row.get("input_sha256")))throw new AdminService.Rejected(409,"ingestion_source_changed");
            if(!"archive".equals(row.get("task_type")))throw new IllegalArgumentException("Archive cursor required");
            var captures=db.queryForList("SELECT frame_id,media_offset_ms AS offset_ms,payload_sha256 AS sha256,receipt FROM capture_outbox WHERE task_id=? AND source_type='archive' AND media_offset_ms>? ORDER BY media_offset_ms,frame_id LIMIT 513",task,row.get("safe_through_ms"));
            if(captures.size()>512)throw new AdminService.Rejected(409,"source_replay_window_full");return Map.of("items",captures.stream().map(IngestionQueries::external).toList());});
    }
    public Map<String,Object> receipts(UUID task,ReceiptLookup body){
        enabled();if(body==null||body.frameIds()==null||body.frameIds().isEmpty()||body.frameIds().size()>512||body.frameIds().stream().anyMatch(Objects::isNull)||new HashSet<>(body.frameIds()).size()!=body.frameIds().size())throw new IllegalArgumentException("Invalid receipt lookup");worker(body.workerId(),body.generation(),1);
        return tx.execute(status->{policy.lock(db);var row=owned(task,body.workerId(),body.generation());if(!Objects.equals(body.inputSha256(),row.get("input_sha256")))throw new AdminService.Rejected(409,"ingestion_source_changed");
            var args=new ArrayList<Object>();args.add(task);args.addAll(body.frameIds());String slots=String.join(",",Collections.nCopies(body.frameIds().size(),"?"));
            var values=db.queryForList("SELECT frame_id,media_offset_ms AS offset_ms,payload_sha256 AS sha256,receipt FROM capture_outbox WHERE task_id=? AND frame_id IN ("+slots+") ORDER BY frame_id",args.toArray());return Map.of("items",values.stream().map(IngestionQueries::external).toList());});
    }
    public Map<String,Object> progress(UUID task,Progress body){
        enabled();if(body==null||body.checkpointId()==null||body.inputSha256()==null||!body.inputSha256().matches("[a-f0-9]{64}")||body.observedThroughMs()<0||body.observedThroughMs()>604800000L||body.safeThroughMs()!=null&&(body.safeThroughMs()<0||body.safeThroughMs()>body.observedThroughMs())||body.gaps()==null||body.gaps().size()>12||body.gaps().entrySet().stream().anyMatch(entry->!entry.getKey().matches("[a-z_]{1,60}")||entry.getValue()==null||entry.getValue()<0||entry.getValue()>1000000000L))throw new IllegalArgumentException("Invalid source progress");worker(body.workerId(),body.generation(),1);
        String sha=CanonicalJson.encode(object(JSON.writeValueAsString(body)),16384).sha256();
        return tx.execute(status->{policy.lock(db);var prior=db.queryForList("SELECT task_id,worker_id,generation,input_sha256,request_sha256,response::text AS payload FROM ingestion_progress_receipts WHERE checkpoint_id=?",body.checkpointId());
            if(!prior.isEmpty()){
                var old=prior.getFirst();if(!task.equals(old.get("task_id"))||!body.workerId().equals(old.get("worker_id"))||body.generation()!=((Number)old.get("generation")).longValue()||!body.inputSha256().equals(old.get("input_sha256"))||!sha.equals(old.get("request_sha256")))throw new AdminService.Rejected(409,"command_request_conflict");return object((String)old.get("payload"));
            }
            var row=owned(task,body.workerId(),body.generation());if(!body.inputSha256().equals(row.get("input_sha256")))throw new AdminService.Rejected(409,"ingestion_source_changed");
            if(!"archive".equals(row.get("task_type")))throw new IllegalArgumentException("Archive progress required");
            long safe=body.safeThroughMs()==null?((Number)row.get("safe_through_ms")).longValue():body.safeThroughMs();
            if(body.observedThroughMs()<((Number)row.get("observed_through_ms")).longValue()||safe<((Number)row.get("safe_through_ms")).longValue()||safe<((Number)row.get("start_ms")).longValue()||row.get("end_ms") instanceof Number end&&body.observedThroughMs()>end.longValue())throw new AdminService.Rejected(409,"ingestion_progress_conflict");
            if(body.safeThroughMs()!=null&&body.gaps().values().stream().anyMatch(value->value>0))throw new AdminService.Rejected(409,"source_checkpoint_gap");
            if(body.readerFinished()&&body.safeThroughMs()==null)throw new IllegalArgumentException("Reader completion requires safe progress");
            if(body.lastFrameId()!=null&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM capture_outbox WHERE task_id=? AND frame_id=? AND media_offset_ms<=?)",Boolean.class,task,body.lastFrameId(),safe)))throw new AdminService.Rejected(409,"source_capture_pending");
            db.update("UPDATE ingestion_task_specs SET safe_through_ms=?,observed_through_ms=?,reader_finished=?,last_progress_at=clock_timestamp() WHERE task_id=?",safe,body.observedThroughMs(),body.readerFinished(),task);
            var response=Map.<String,Object>of("task_id",task.toString(),"checkpoint_id",body.checkpointId().toString(),"safe_through_ms",safe,"observed_through_ms",body.observedThroughMs(),"reader_finished",body.readerFinished());
            db.update("INSERT INTO ingestion_progress_receipts(checkpoint_id,task_id,worker_id,generation,input_sha256,request_sha256,response) VALUES(?,?,?,?,?,?,?::jsonb)",body.checkpointId(),task,body.workerId(),body.generation(),body.inputSha256(),sha,CanonicalJson.encode(response,16384).payload());return response;
        });
    }
}
