package cn.trial.videosearch.evidence;

import cn.trial.videosearch.ingest.AnalysisQueuePolicy;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Independent, fenced archive encoding jobs. File verification stays outside write transactions. */
@Service
public class MediaJobs {
    public static final String PROFILE="archive-h264-10fps-v1";
    private static final long MAX_OFFSET_MS=9007199254710991L;
    private static final Set<String> FAILURES=Set.of("media_source_changed","media_source_unavailable","media_input_invalid","media_capacity_reached","media_store_busy","media_worker_error","media_timeout","job_timeout","consumer_interrupted","result_commit_unavailable");
    private final JdbcTemplate db;private final SearchOrchestrator search;private final MediaClipFiles files;private final MediaClips clips;
    private final TransactionTemplate tx;private final boolean enabled;private final int pendingLimit;private final AnalysisQueuePolicy policy;
    public MediaJobs(JdbcTemplate db,SearchOrchestrator search,MediaClipFiles files,MediaClips clips,PlatformTransactionManager manager,
        @Value("${VIDEO_MEDIA_JOBS_ENABLED:false}")boolean enabled,@Value("${VIDEO_MEDIA_JOBS_PENDING_LIMIT:200}")int pending,
        @Value("${VIDEO_MEDIA_JOBS_ACTIVE_LIMIT:1}")int active,@Value("${VIDEO_MEDIA_JOBS_MAX_ATTEMPTS:3}")int attempts){
        if(pending<1||pending>2000||active<1||active>4||attempts<1||attempts>10)throw new IllegalArgumentException("Invalid media queue policy");
        this.db=db;this.search=search;this.files=files;this.clips=clips;tx=new TransactionTemplate(manager);this.enabled=enabled;pendingLimit=pending;policy=new AnalysisQueuePolicy(active,attempts,5,300);
    }
    public record Create(UUID requestId,UUID sourceId,long startMs,long endMs){}
    public record Retry(UUID requestId,long revision){}
    public record Job(UUID jobId,UUID sourceId,String cameraId,long startMs,long endMs,String profileVersion,String sourceFingerprint,String sourceSha256,long sourceBytes,String inputSha256,int attempts,long generation,Instant expiresAt){}
    public record Complete(UUID jobId,String workerId,long generation,String inputSha256,String manifestSha256){}
    private void requireEnabled(){if(!enabled)throw new InputRejected(404,"media_jobs_disabled");}
    private void lock(){db.execute("SELECT pg_advisory_xact_lock(918273647)");}
    private boolean claimsEnabled(){return Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));}
    private void capacity(){if(db.queryForObject("SELECT count(*) FROM media_production_jobs WHERE state IN ('queued','active')",Long.class)>=pendingLimit)throw new InputRejected(429,"media_jobs_full");}
    private Optional<UUID> previous(UUID request,String sha){
        var rows=db.queryForList("SELECT job_id,request_sha256 FROM media_production_requests WHERE request_id=?",request);
        if(rows.isEmpty())return Optional.empty();if(!sha.equals(rows.getFirst().get("request_sha256")))throw new InputRejected(409,"media_request_conflict");return Optional.of((UUID)rows.getFirst().get("job_id"));
    }
    public Map<String,Object> create(Create body){
        requireEnabled();if(body==null||body.requestId()==null||body.sourceId()==null||body.startMs()<0||body.startMs()>MAX_OFFSET_MS||body.endMs()<=body.startMs()||body.endMs()-body.startMs()<2000||body.endMs()-body.startMs()>30000)throw new IllegalArgumentException("Invalid media job window");
        String requestSha=CanonicalJson.encode(Map.of("operation","create","source_id",body.sourceId().toString(),"start_ms",body.startMs(),"end_ms",body.endMs()),8192).sha256();
        var replay=previous(body.requestId(),requestSha);if(replay.isPresent())return read(replay.get());
        var sources=db.queryForList("SELECT camera_id FROM sources WHERE source_id=?",body.sourceId());
        if(sources.size()!=1)throw new InputRejected(404,"media_source_unavailable");String camera=(String)sources.getFirst().get("camera_id");
        String fingerprint=clips.source(body.sourceId(),camera);var original=files.inspectRecording(camera);
        var input=new LinkedHashMap<String,Object>();input.putAll(Map.of("source_id",body.sourceId().toString(),"camera_id",camera,"start_ms",body.startMs(),"end_ms",body.endMs(),"profile_version",PROFILE,"source_fingerprint",fingerprint,"source",original));
        String inputSha=CanonicalJson.encode(input,8192).sha256();UUID id=tx.execute(status->{
            lock();var duplicate=previous(body.requestId(),requestSha);if(duplicate.isPresent())return duplicate.get();
            db.queryForList("SELECT source_id FROM sources WHERE source_id=? FOR SHARE",body.sourceId());
            if(!fingerprint.equals(clips.source(body.sourceId(),camera)))throw new InputRejected(409,"media_source_changed");
            var existing=db.queryForList("SELECT job_id FROM media_production_jobs WHERE input_sha256=?",inputSha);UUID job;
            if(existing.isEmpty()){
                capacity();job=UUID.randomUUID();
                db.update("INSERT INTO media_production_jobs(job_id,source_id,camera_id,source_fingerprint,source_sha256,source_bytes,start_ms,end_ms,profile_version,input_sha256) VALUES(?,?,?,?,?,?,?,?,?,?)",job,body.sourceId(),camera,fingerprint,original.get("sha256"),original.get("bytes"),body.startMs(),body.endMs(),PROFILE,inputSha);
                db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES('trial-service-account','create_media_job','queued',?)",body.requestId());
            }else job=(UUID)existing.getFirst().get("job_id");
            saveRequest(body.requestId(),requestSha,job);return job;
        });return read(id);
    }
    private void saveRequest(UUID request,String sha,UUID job){db.update("INSERT INTO media_production_requests(request_id,request_sha256,job_id) VALUES(?,?,?)",request,sha,job);}
    private static void lease(String worker,int seconds){if(worker==null||!worker.matches("[A-Za-z0-9_.-]{1,70}")||seconds<60||seconds>600)throw new IllegalArgumentException("Invalid media lease");}
    /** Only archive inputs remain; a requested capability list no longer changes the claim. */
    public Optional<Job> claim(String worker,int seconds,List<String> supported){
        return claim(worker,seconds);
    }
    public Optional<Job> claim(String worker,int seconds){
        requireEnabled();lease(worker,seconds);
        return tx.execute(status->{
            lock();if(!claimsEnabled())return Optional.empty();
            db.update("UPDATE media_production_jobs SET state=CASE WHEN attempts+1>=? THEN 'failed' ELSE 'queued' END,attempts=attempts+1,revision=revision+1,last_error_code='job_timeout',lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(LEAST(300,5*power(2,LEAST(attempts,10))) * interval '1 second'),updated_at=clock_timestamp() WHERE state='active' AND lease_expires_at<=clock_timestamp()",policy.maxAttempts());
            if(db.queryForObject("SELECT count(*) FROM media_production_jobs WHERE state='active'",Long.class)>=policy.activeLimit())return Optional.empty();
            var cameras=new TreeSet<>(search.readableCameras());if(cameras.isEmpty())return Optional.empty();var arguments=new ArrayList<Object>();arguments.add(policy.maxAttempts());arguments.addAll(cameras);arguments.add(worker);arguments.add(seconds);
            var rows=db.query("""
                WITH candidate AS(SELECT job_id FROM media_production_jobs WHERE state='queued' AND attempts<? AND camera_id IN (%s) AND next_attempt_at<=clock_timestamp() ORDER BY next_attempt_at,created_at,job_id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE media_production_jobs j SET state='active',revision=revision+1,lease_owner=?,lease_generation=lease_generation+1,lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                FROM candidate WHERE j.job_id=candidate.job_id RETURNING j.*
                """.formatted(String.join(",",Collections.nCopies(cameras.size(),"?"))),(rs,n)->new Job(rs.getObject("job_id",UUID.class),rs.getObject("source_id",UUID.class),rs.getString("camera_id"),rs.getLong("start_ms"),rs.getLong("end_ms"),rs.getString("profile_version"),rs.getString("source_fingerprint"),rs.getString("source_sha256"),rs.getLong("source_bytes"),rs.getString("input_sha256"),rs.getInt("attempts"),rs.getLong("lease_generation"),rs.getTimestamp("lease_expires_at").toInstant()),arguments.toArray());
            var claimed=rows.stream().findFirst();
            if(claimed.isPresent()){
                var job=claimed.get();boolean valid;
                try{valid=job.sourceFingerprint().equals(clips.source(job.sourceId(),job.cameraId()));}catch(InputRejected changed){valid=false;}
                if(!valid){db.update("UPDATE media_production_jobs SET state='failed',attempts=attempts+1,revision=revision+1,last_error_code='media_source_changed',lease_owner=NULL,lease_expires_at=NULL,updated_at=clock_timestamp() WHERE job_id=?",job.jobId());return Optional.empty();}
            }
            return claimed;
        });
    }
    public boolean renew(UUID id,String worker,long generation,int seconds){requireEnabled();lease(worker,seconds);if(id==null||generation<1)throw new IllegalArgumentException("Invalid media generation");return db.update("UPDATE media_production_jobs SET lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()",seconds,id,worker,generation)==1;}
    public boolean fail(UUID id,String worker,long generation,String code){
        requireEnabled();lease(worker,60);if(id==null||generation<1||code==null||!FAILURES.contains(code))throw new IllegalArgumentException("Invalid media failure");
        return tx.execute(status->{var rows=db.queryForList("SELECT attempts FROM media_production_jobs WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp() FOR UPDATE",id,worker,generation);
            if(rows.isEmpty())return false;int attempts=((Number)rows.getFirst().get("attempts")).intValue()+1;
            db.update("UPDATE media_production_jobs SET state=?,attempts=?,revision=revision+1,last_error_code=?,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=?",attempts>=policy.maxAttempts()||Set.of("media_source_changed","media_input_invalid").contains(code)?"failed":"queued",attempts,code,policy.retryDelaySeconds(attempts),id);return true;});
    }
    private Map<String,Object> owned(Complete body){
        if(body==null||body.jobId()==null||body.generation()<1||body.inputSha256()==null||!body.inputSha256().matches("[a-f0-9]{64}")||body.manifestSha256()==null||!body.manifestSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid media completion");lease(body.workerId(),60);
        var rows=db.queryForList("SELECT *,lease_expires_at>clock_timestamp() AS alive FROM media_production_jobs WHERE job_id=? AND lease_owner=? AND lease_generation=?",body.jobId(),body.workerId(),body.generation());
        if(rows.size()!=1||!body.inputSha256().equals(rows.getFirst().get("input_sha256")))throw new InputRejected(409,"media_lease_expired");
        var row=rows.getFirst();if("completed".equals(row.get("state"))){if(!body.manifestSha256().equals(row.get("manifest_sha256")))throw new InputRejected(409,"media_completion_conflict");}
        else if(!"active".equals(row.get("state"))||!Boolean.TRUE.equals(row.get("alive")))throw new InputRejected(409,"media_lease_expired");return row;
    }
    public Map<String,Object> complete(Complete body){
        requireEnabled();var row=owned(body);String camera=(String)row.get("camera_id");
        verifySource(row);
        var asset=files.verify(body.jobId(),body.manifestSha256(),MediaClipFiles.Mode.MANIFEST,-1);
        if(!asset.sourceId().equals(row.get("source_id"))||!asset.camera().equals(camera)||!asset.sourceSha256().equals(row.get("source_sha256"))||asset.sourceBytes()!=((Number)row.get("source_bytes")).longValue()
            ||((Number)asset.manifest().get("requested_start_ms")).longValue()!=((Number)row.get("start_ms")).longValue()||((Number)asset.manifest().get("requested_end_ms")).longValue()!=((Number)row.get("end_ms")).longValue())throw new InputRejected(409,"media_completion_conflict");
        // Registration can survive a lost lease/ack; its immutable output is
        // reused by the next attempt, while job completion remains fenced.
        clips.register(new MediaClips.Register(body.jobId(),body.manifestSha256(),body.workerId()));
        tx.executeWithoutResult(status->{db.queryForList("SELECT job_id FROM media_production_jobs WHERE job_id=? FOR UPDATE",body.jobId());db.queryForList("SELECT source_id FROM sources WHERE source_id=? FOR SHARE",row.get("source_id"));var current=owned(body);
            verifySource(current);
            if(!"completed".equals(current.get("state"))){db.update("UPDATE media_production_jobs SET state='completed',manifest_sha256=?,lease_expires_at=NULL,last_error_code=NULL,revision=revision+1,updated_at=clock_timestamp() WHERE job_id=?",body.manifestSha256(),body.jobId());db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'complete_media_job','completed',?)",body.workerId(),body.jobId());}});
        return Map.of("completed",true,"jobId",body.jobId().toString(),"clipId",body.jobId().toString(),"inputSha256",body.inputSha256(),"manifestSha256",body.manifestSha256());
    }
    public Map<String,Object> retry(UUID id,Retry body){
        requireEnabled();if(id==null||body==null||body.requestId()==null||body.revision()<1)throw new IllegalArgumentException("Invalid media retry");read(id);
        String sha=CanonicalJson.encode(Map.of("operation","retry","job_id",id.toString(),"revision",body.revision()),8192).sha256();
        tx.executeWithoutResult(status->{lock();var previous=previous(body.requestId(),sha);if(previous.isPresent()){if(!id.equals(previous.get()))throw new InputRejected(409,"media_request_conflict");return;}
            var row=readRow(id,true);if(!"failed".equals(row.get("state"))||((Number)row.get("revision")).longValue()!=body.revision())throw new InputRejected(409,"media_revision_conflict");capacity();
            verifySource(row);
            db.update("UPDATE media_production_jobs SET state='queued',attempts=0,revision=revision+1,last_error_code=NULL,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE job_id=?",id);saveRequest(body.requestId(),sha,id);
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES('trial-service-account','retry_media_job','queued',?)",body.requestId());});return read(id);
    }
    private Map<String,Object> readRow(UUID id,boolean locked){
        var rows=db.queryForList("SELECT *,lease_expires_at<=clock_timestamp() AS lease_expired,next_attempt_at>clock_timestamp() AS retry_wait FROM media_production_jobs WHERE job_id=?"+(locked?" FOR UPDATE":""),id);
        if(rows.size()!=1||!search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"media_job_unavailable");return rows.getFirst();
    }
    public Map<String,Object> read(UUID id){requireEnabled();return summary(readRow(id,false),claimsEnabled());}
    private void verifySource(Map<String,Object> row){if(!row.get("source_fingerprint").equals(clips.source((UUID)row.get("source_id"),(String)row.get("camera_id"))))throw new InputRejected(409,"media_source_changed");}
    public Map<String,Object> list(String camera,int limit,UUID after){
        requireEnabled();if(!search.mayReadCamera(camera))throw new InputRejected(404,"media_job_unavailable");if(limit<1||limit>50)throw new IllegalArgumentException("Invalid media page");
        var rows=db.queryForList("SELECT *,lease_expires_at<=clock_timestamp() AS lease_expired,next_attempt_at>clock_timestamp() AS retry_wait FROM media_production_jobs WHERE camera_id=? AND (?::uuid IS NULL OR job_id>?) ORDER BY job_id LIMIT ?",camera,after,after,limit+1);
        boolean more=rows.size()>limit,claims=claimsEnabled();var page=rows.subList(0,Math.min(limit,rows.size()));var result=new LinkedHashMap<String,Object>();result.put("items",page.stream().map(row->summary(row,claims)).toList());result.put("has_more",more);result.put("next_cursor",more?page.getLast().get("job_id").toString():null);result.put("policy",policy.snapshot(pendingLimit));result.put("claims_enabled",claims);return result;
    }
    private static Map<String,Object> summary(Map<String,Object> row,boolean claims){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("job_id","source_id","camera_id","start_ms","end_ms","profile_version","input_sha256","state","attempts","revision","next_attempt_at","last_error_code","manifest_sha256","created_at","updated_at","lease_expired")){var value=row.get(key);result.put(key,value instanceof UUID id?id.toString():value instanceof Timestamp time?time.toInstant().toString():value);}
        String state=(String)row.get("state");result.put("claims_enabled",claims);result.put("dispatch_state","queued".equals(state)?!claims?"paused":Boolean.TRUE.equals(row.get("retry_wait"))?"retry_wait":"waiting":"active".equals(state)?Boolean.TRUE.equals(row.get("lease_expired"))?"lease_expired":"running":state);
        if("completed".equals(state))result.put("clip_id",row.get("job_id").toString());return result;
    }
}
