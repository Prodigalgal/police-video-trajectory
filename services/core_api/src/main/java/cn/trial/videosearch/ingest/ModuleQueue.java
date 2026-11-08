package cn.trial.videosearch.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Independent module projections over a previously validated frame result. */
@Service
public class ModuleQueue {
    private static final Set<String> FAILURES=Set.of("module_unavailable","invalid_module_source",
            "result_commit_unavailable","job_timeout","consumer_interrupted");
    private final JdbcTemplate db;
    private final ModuleQueuePolicy policy;
    private final ModuleResultSource sources=new ModuleResultSource();
    private final ObjectMapper json=new ObjectMapper();
    public record Job(UUID jobId,UUID frameId,String moduleKey,UUID sourceId,String sourceSha256,
                      int attempts,long generation,Instant expiresAt){}
    public record Completion(UUID jobId,String workerId,long generation,UUID sourceId,String sourceSha256){}
    public static final class CapacityExceeded extends IllegalStateException {
        public CapacityExceeded(){super("module queue capacity unavailable");}
    }
    public static final class RecoveryRejected extends IllegalStateException {
        public final int status;
        public final String code;
        public RecoveryRejected(int status,String code){super("module recovery rejected");this.status=status;this.code=code;}
    }

    public ModuleQueue(JdbcTemplate db,ModuleQueuePolicy policy){this.db=db;this.policy=policy;}
    public Map<String,Object> policy(){return policy.snapshot();}
    public void lockAdmission(){db.execute("SELECT pg_advisory_xact_lock(918273645)");}
    public boolean hasFrameCapacity(){return pending()+3<=policy.pendingLimit();}
    private long pending(){return db.queryForObject("SELECT count(*) FROM analysis_module_jobs WHERE state IN ('queued','active')",Long.class);}

    @Transactional
    public void enqueue(UUID frameId,TargetPipeline.Completion completion){
        lockAdmission();
        var encoded=sources.encode(frameId,completion);
        var source=sources.decode(encoded.payload(),encoded.sha256(),frameId);
        UUID sourceId=UUID.nameUUIDFromBytes(("analysis-source:"+frameId).getBytes(StandardCharsets.UTF_8));
        long saved=db.queryForObject("SELECT count(*) FROM analysis_result_sources WHERE frame_id=?",Long.class,frameId);
        if(saved>0)readSource(sourceId,frameId,encoded.sha256());
        var modules=db.queryForList("SELECT module_key,target_ids::text,result::text,completed_at FROM analysis_modules WHERE frame_id=? ORDER BY module_key",frameId);
        var expectedKinds=new HashSet<String>();expectedKinds.add("scene_context");
        for(var target:completion.targets())expectedKinds.add(target.objectType()+"_attributes");
        if(!expectedKinds.equals(new HashSet<>(modules.stream().map(row->(String)row.get("module_key")).toList())))
            throw new IllegalStateException("planned analysis module missing");
        int missing=0;
        for(var module:modules){
            String kind=(String)module.get("module_key");
            var body=sources.project(kind,source);var projection=sources.encodeValue(body);
            verifyMembership(kind,body,(String)module.get("target_ids"));
            if(projection.payload().length()>256000)throw new ModuleResultSource.Invalid();
            if((module.get("result")==null)!=(module.get("completed_at")==null))throw new IllegalStateException("module state is inconsistent");
            if(module.get("result")!=null){
                Integer same=db.queryForObject("SELECT count(*) FROM analysis_modules WHERE frame_id=? AND module_key=? AND result=CAST(? AS jsonb)",Integer.class,frameId,kind,projection.payload());
                if(same==null || same!=1)throw new IllegalStateException("conflicting completed module replay");
            }else if(db.queryForObject("SELECT count(*) FROM analysis_module_jobs WHERE frame_id=? AND module_key=?",Long.class,frameId,kind)==0)missing++;
        }
        if(missing==0 && saved==0)return;
        if(missing>0 && pending()+missing>policy.pendingLimit())throw new CapacityExceeded();
        db.update("""
                INSERT INTO analysis_result_sources(source_id,frame_id,model_id,prompt_version,source_payload,source_sha256)
                VALUES(?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT(frame_id) DO NOTHING
                """,sourceId,frameId,completion.modelId(),completion.promptVersion(),encoded.payload(),encoded.sha256());
        if(saved==0)readSource(sourceId,frameId,encoded.sha256());
        for(var module:modules){
            if(module.get("result")!=null)continue;
            String kind=(String)module.get("module_key");
            UUID jobId=UUID.nameUUIDFromBytes(("module:"+frameId+":"+kind).getBytes(StandardCharsets.UTF_8));
            db.update("""
                    INSERT INTO analysis_module_jobs(job_id,frame_id,module_key,source_id,state) VALUES(?,?,?,?,'queued')
                    ON CONFLICT(frame_id,module_key) DO NOTHING
                    """,jobId,frameId,kind,sourceId);
        }
    }

    @Transactional
    public Optional<Job> claim(String worker,int seconds,String kind){
        validateLease(worker,seconds);
        if(kind!=null)policy.kindLimit(kind);
        lockAdmission();
        if(!claimsEnabled())return Optional.empty();
        recoverExpired();
        long active=db.queryForObject("SELECT count(*) FROM analysis_module_jobs WHERE state='active' AND lease_expires_at>clock_timestamp()",Long.class);
        if(active>=policy.activeLimit())return Optional.empty();
        var rows=db.query("""
                WITH candidate AS (
                  SELECT j.job_id FROM analysis_module_jobs j WHERE j.state='queued' AND j.attempts<?
                    AND j.next_attempt_at<=clock_timestamp() AND (?::text IS NULL OR j.module_key=?)
                    AND (SELECT count(*) FROM analysis_module_jobs a WHERE a.module_key=j.module_key
                      AND a.state='active' AND a.lease_expires_at>clock_timestamp()) <
                      CASE j.module_key WHEN 'person_attributes' THEN ? WHEN 'vehicle_attributes' THEN ? ELSE ? END
                  ORDER BY j.next_attempt_at,j.created_at,j.job_id FOR UPDATE OF j SKIP LOCKED LIMIT 1
                )
                UPDATE analysis_module_jobs j SET state='active',lease_owner=?,lease_generation=j.lease_generation+1,
                  lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                FROM candidate,analysis_result_sources s WHERE j.job_id=candidate.job_id AND s.source_id=j.source_id
                RETURNING j.job_id,j.frame_id,j.module_key,j.source_id,s.source_sha256,j.attempts,j.lease_generation,j.lease_expires_at
                """,(rs,n)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                        rs.getObject(4,UUID.class),rs.getString(5),rs.getInt(6),rs.getLong(7),rs.getTimestamp(8).toInstant()),
                policy.maxAttempts(),kind,kind,policy.kindLimit("person_attributes"),policy.kindLimit("vehicle_attributes"),
                policy.kindLimit("scene_context"),worker,seconds);
        return rows.stream().findFirst();
    }

    public boolean renew(UUID job,String worker,long generation,int seconds){
        validateLease(worker,seconds);
        if(job==null || generation<1)throw new IllegalArgumentException("invalid module lease");
        return db.update("""
                UPDATE analysis_module_jobs SET lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
                """,seconds,job,worker,generation)==1;
    }

    @Transactional
    public boolean fail(UUID job,String worker,long generation,String code){
        if(job==null || !owner(worker) || generation<1 || code==null || !FAILURES.contains(code))throw new IllegalArgumentException("invalid module failure");
        var rows=db.queryForList("""
                SELECT attempts FROM analysis_module_jobs WHERE job_id=? AND state='active' AND lease_owner=?
                  AND lease_generation=? AND lease_expires_at>clock_timestamp() FOR UPDATE
                """,job,worker,generation);
        if(rows.isEmpty())return false;
        int attempts=((Number)rows.getFirst().get("attempts")).intValue()+1;
        db.update("""
                UPDATE analysis_module_jobs SET state=?,attempts=?,last_error_code=?,lease_owner=NULL,lease_expires_at=NULL,
                  next_attempt_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=?
                """,attempts>=policy.maxAttempts()?"failed":"queued",attempts,code,policy.retryDelaySeconds(attempts),job);
        return true;
    }

    @Transactional
    public Map<String,Object> complete(Completion completion){
        validateCompletion(completion);lockAdmission();
        var rows=db.queryForList("""
                SELECT j.frame_id,j.module_key,j.state,j.source_id,m.target_ids::text,m.result::text,m.completed_at
                FROM analysis_module_jobs j JOIN analysis_modules m USING(frame_id,module_key)
                WHERE j.job_id=? AND j.lease_owner=? AND j.lease_generation=? AND
                  ((j.state='active' AND j.lease_expires_at>clock_timestamp()) OR j.state='completed') FOR UPDATE OF j,m
                """,completion.jobId(),completion.workerId(),completion.generation());
        if(rows.size()!=1)throw new IllegalStateException("module lease expired or replaced");
        var row=rows.getFirst();UUID frame=(UUID)row.get("frame_id");String kind=(String)row.get("module_key");
        if(!completion.sourceId().equals(row.get("source_id")))throw new ModuleResultSource.Invalid();
        var source=readSource(completion.sourceId(),frame,completion.sourceSha256());
        var projected=sources.project(kind,source);verifyMembership(kind,projected,(String)row.get("target_ids"));
        var encoded=sources.encodeValue(projected);
        if(encoded.payload().length()>256000)throw new ModuleResultSource.Invalid();
        if("completed".equals(row.get("state"))){
            Integer same=db.queryForObject("SELECT count(*) FROM analysis_modules WHERE frame_id=? AND module_key=? AND completed_at IS NOT NULL AND result=CAST(? AS jsonb)",Integer.class,frame,kind,encoded.payload());
            if(same==null || same!=1)throw new IllegalStateException("completed module differs");
        }else{
            if(row.get("result")!=null || row.get("completed_at")!=null)throw new IllegalStateException("module result already present");
            db.update("UPDATE analysis_modules SET result=CAST(? AS jsonb),completed_at=clock_timestamp() WHERE frame_id=? AND module_key=?",encoded.payload(),frame,kind);
            int live=db.update("""
                    UPDATE analysis_module_jobs SET state='completed',lease_expires_at=NULL,updated_at=clock_timestamp()
                    WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
                    """,completion.jobId(),completion.workerId(),completion.generation());
            if(live!=1)throw new IllegalStateException("module lease expired before commit");
        }
        return Map.of("completed",true,"jobId",completion.jobId(),"moduleKey",kind,"sourceId",completion.sourceId(),
                "sourceSha256",completion.sourceSha256(),"resultSha256",encoded.sha256());
    }

    private Map<String,Object> readSource(UUID sourceId,UUID frame,String expected){
        var rows=db.queryForList("SELECT model_id,prompt_version,source_payload::text,source_sha256 FROM analysis_result_sources WHERE source_id=? AND frame_id=?",sourceId,frame);
        if(rows.size()!=1 || !Objects.equals(rows.getFirst().get("source_sha256"),expected))throw new ModuleResultSource.Invalid();
        var row=rows.getFirst();var source=sources.decode((String)row.get("source_payload"),expected,frame);
        if(!Objects.equals(row.get("model_id"),source.get("model_id")) || !Objects.equals(row.get("prompt_version"),source.get("prompt_version")))throw new ModuleResultSource.Invalid();
        return source;
    }

    private void verifyMembership(String kind,Map<String,Object> projection,String targetJson){
        var expected=json.readValue(targetJson,List.class);var actual=new ArrayList<Object>();
        if(!kind.equals("scene_context"))for(Object raw:(List<?>)projection.get("targets")){
            if(!(raw instanceof Map<?,?> target) || !(target.get("targetId") instanceof String))throw new ModuleResultSource.Invalid();
            actual.add(target.get("targetId"));
        }
        if(actual.size()!=expected.size() || new HashSet<>(actual).size()!=actual.size() || !new HashSet<>(actual).equals(new HashSet<>(expected)))throw new ModuleResultSource.Invalid();
    }

    private void recoverExpired(){
        db.update("""
                UPDATE analysis_module_jobs SET state='failed',lease_owner=NULL,lease_expires_at=NULL,
                  last_error_code=coalesce(last_error_code,'retry_exhausted'),updated_at=clock_timestamp()
                WHERE state='queued' AND attempts>=?
                """,policy.maxAttempts());
        db.update("""
                UPDATE analysis_module_jobs SET state=CASE WHEN attempts+1>=? THEN 'failed' ELSE 'queued' END,
                  attempts=attempts+1,last_error_code='lease_expired',lease_owner=NULL,lease_expires_at=NULL,
                  next_attempt_at=clock_timestamp()+(LEAST(?,? * power(2,LEAST(attempts,20))) * interval '1 second'),updated_at=clock_timestamp()
                WHERE state='active' AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp())
                """,policy.maxAttempts(),policy.retryMaxSeconds(),policy.retryBaseSeconds());
    }

    @Transactional
    public Map<String,Object> retry(UUID job,UUID request,long revision,String actor,String reason){
        if(job==null || request==null || revision<1 || actor==null || !actor.matches("[A-Za-z0-9_.@-]{1,100}") ||
                reason==null || reason.isBlank() || reason.length()>300 || reason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid module recovery");
        lockAdmission();
        var prior=db.queryForList("SELECT job_id,expected_revision,actor_id,reason,receipt::text FROM analysis_module_recoveries WHERE request_id=?",request);
        if(!prior.isEmpty()){
            var old=prior.getFirst();
            if(!job.equals(old.get("job_id")) || ((Number)old.get("expected_revision")).longValue()!=revision ||
                    !actor.equals(old.get("actor_id")) || !reason.equals(old.get("reason")))throw new RecoveryRejected(409,"module_recovery_conflict");
            return json.readValue((String)old.get("receipt"),Map.class);
        }
        if(!claimsEnabled())throw new RecoveryRejected(409,"processing_paused");
        var rows=db.queryForList("""
                SELECT j.frame_id,j.module_key,j.source_id,j.state,j.lease_generation,m.result,m.completed_at,m.target_ids::text,r.source_sha256,s.enabled,c.enabled AS camera_enabled
                FROM analysis_module_jobs j JOIN analysis_modules m USING(frame_id,module_key)
                JOIN analysis_result_sources r ON r.source_id=j.source_id JOIN capture_outbox o ON o.frame_id=j.frame_id
                JOIN sources s ON s.source_id=(SELECT source_id FROM processing_tasks WHERE task_id=o.task_id)
                JOIN cameras c ON c.camera_id=o.camera_id WHERE j.job_id=? FOR UPDATE OF j,m,s,c
                """,job);
        if(rows.size()!=1)throw new RecoveryRejected(404,"module_job_unavailable");
        var row=rows.getFirst();
        if(!Boolean.TRUE.equals(row.get("enabled")) || !Boolean.TRUE.equals(row.get("camera_enabled")))throw new RecoveryRejected(409,"processing_paused");
        if(!"failed".equals(row.get("state")) || ((Number)row.get("lease_generation")).longValue()!=revision || row.get("result")!=null || row.get("completed_at")!=null)
            throw new RecoveryRejected(409,"state_revision_conflict");
        var source=readSource((UUID)row.get("source_id"),(UUID)row.get("frame_id"),(String)row.get("source_sha256"));
        verifyMembership((String)row.get("module_key"),sources.project((String)row.get("module_key"),source),(String)row.get("target_ids"));
        if(pending()>=policy.pendingLimit())throw new CapacityExceeded();
        long generation=revision+1;
        db.update("UPDATE analysis_module_jobs SET state='queued',attempts=0,lease_owner=NULL,lease_expires_at=NULL,lease_generation=?,last_error_code=NULL,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE job_id=?",generation,job);
        var receipt=Map.<String,Object>of("job_id",job,"request_id",request,"revision",generation,"applied_state","queued");
        db.update("INSERT INTO analysis_module_recoveries(request_id,job_id,expected_revision,actor_id,reason,receipt) VALUES(?,?,?,?,?,CAST(? AS jsonb))",request,job,revision,actor,reason,json.writeValueAsString(receipt));
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'analysis.module.retry','queued',?)",actor,request);
        return receipt;
    }

    public List<Map<String,Object>> status(){return db.queryForList("SELECT module_key,state,count(*) AS count FROM analysis_module_jobs GROUP BY module_key,state ORDER BY module_key,state");}
    private boolean claimsEnabled(){return Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));}
    private static boolean owner(String worker){return worker!=null && worker.matches("[A-Za-z0-9_.-]{1,100}");}
    private static void validateLease(String worker,int seconds){if(!owner(worker) || seconds<1 || seconds>600)throw new IllegalArgumentException("invalid module lease");}
    private static void validateCompletion(Completion completion){
        if(completion==null || completion.jobId()==null || !owner(completion.workerId()) || completion.generation()<1 || completion.sourceId()==null ||
                completion.sourceSha256()==null || !completion.sourceSha256().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid module completion");
    }
}
