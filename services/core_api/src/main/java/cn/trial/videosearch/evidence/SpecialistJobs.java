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
import java.util.*;

/** Dedicated recognition has its own quota and result; frame/module success is untouched. */
@Service
public class SpecialistJobs {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    private static final Set<String> FAILURES=Set.of("specialist_unavailable","specialist_timeout","specialist_input_changed","invalid_specialist_result","result_commit_unavailable","consumer_interrupted","job_timeout");
    private final JdbcTemplate db;private final AnalysisInputs inputs;private final SearchOrchestrator search;private final TransactionTemplate tx;
    private final boolean enabled;private final String profile;private final int capacity;private final AnalysisQueuePolicy policy;
    private final ProcessingCatalog catalog;
    private final SpecialistAdmission admission;
    private final PlateEventResults events;
    public SpecialistJobs(JdbcTemplate db,AnalysisInputs inputs,SearchOrchestrator search,PlatformTransactionManager manager,
        @Value("${VIDEO_SPECIALIST_JOBS_ENABLED:false}")boolean enabled,@Value("${VIDEO_SPECIALIST_PROFILE:}")String profile,
        @Value("${VIDEO_SPECIALIST_PENDING_LIMIT:100}")int capacity,@Value("${VIDEO_SPECIALIST_ACTIVE_LIMIT:1}")int active,@Value("${VIDEO_SPECIALIST_MAX_ATTEMPTS:3}")int attempts,
        ProcessingCatalog catalog,SpecialistAdmission admission,PlateEventResults events){
        if(capacity<1||capacity>2000||active<1||active>4||attempts<1||attempts>10||!profile.isEmpty()&&!profile.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("Invalid specialist configuration");
        this.db=db;this.inputs=inputs;this.search=search;tx=new TransactionTemplate(manager);this.enabled=enabled;this.profile=profile;this.capacity=capacity;policy=new AnalysisQueuePolicy(active,attempts,5,300);
        this.catalog=catalog;this.admission=admission;this.events=events;
    }
    public record Create(UUID requestId,String inputManifestSha256,String operation){}
    public record Retry(UUID requestId,long revision){}
    public record Complete(UUID jobId,String workerId,long generation,String inputSha256,Map<String,Object> result){}
    private void requireEnabled(){if(!enabled)throw new InputRejected(404,"specialist_disabled");}
    private void lock(){db.execute("SELECT pg_advisory_xact_lock(918273648)");}
    private boolean claims(){return Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));}
    private void room(){if(db.queryForObject("SELECT count(*) FROM specialist_analysis_jobs WHERE state IN ('queued','active')",Long.class)>=capacity)throw new InputRejected(429,"specialist_queue_full");}
    public Map<String,Object> capabilities(){
        requireEnabled();var result=new LinkedHashMap<String,Object>();
        if(!catalog.configured())result.putAll(Map.of("configured",!profile.isEmpty(),"profile",profile,"operations",SpecialistResult.OPERATIONS.stream().sorted().toList(),"result_schema","specialist-result-v1"));
        else{
            var snapshot=catalog.current();var bindings=new TreeMap<String,Object>();
            for(String operation:SpecialistResult.OPERATIONS){var module=snapshot.modules().get(operation);if(module instanceof Map<?,?> value&&Boolean.TRUE.equals(value.get("enabled")))bindings.put(operation,Map.of("profile",catalog.binding(snapshot,operation,profile).profile()));}
            result.putAll(Map.of("configured",!bindings.isEmpty(),"profile","","operations",new ArrayList<>(bindings.keySet()),"bindings",bindings,"catalog_revision",snapshot.revision(),"result_schema","specialist-result-v1"));
        }
        if(admission.required())result.put("selection_required",true);return result;
    }
    public Map<String,Object> selection(UUID input){
        requireEnabled();var verified=inputs.metadata(input);var binding=catalog.configured()?catalog.binding("license_plate",profile):new ProcessingCatalog.Binding(profile,null,null,"",0);
        var descriptor=SpecialistResult.input(input,verified,"license_plate",binding.profile().isEmpty()?"selection-preview":binding.profile());
        return admission.preview(descriptor,binding);
    }
    private UUID previous(UUID request,String actor,String sha){
        var rows=db.queryForList("SELECT actor_id,request_sha256,job_id FROM specialist_analysis_requests WHERE request_id=?",request);if(rows.isEmpty())return null;
        if(!actor.equals(rows.getFirst().get("actor_id"))||!sha.equals(rows.getFirst().get("request_sha256")))throw new InputRejected(409,"specialist_request_conflict");return (UUID)rows.getFirst().get("job_id");
    }
    private void request(UUID request,String actor,String sha,UUID job){db.update("INSERT INTO specialist_analysis_requests(request_id,actor_id,request_sha256,job_id) VALUES(?,?,?,?)",request,actor,sha,job);}
    private static String createFingerprint(UUID input,Create body,String actor){
        if(input==null||body==null||body.requestId()==null||actor==null||actor.isBlank()||actor.length()>100||body.inputManifestSha256()==null||!body.inputManifestSha256().matches("[a-f0-9]{64}")||body.operation()==null||!SpecialistResult.OPERATIONS.contains(body.operation()))throw new IllegalArgumentException("Invalid specialist request");
        return CanonicalJson.encode(Map.of("action","create","input_id",input.toString(),"input_manifest_sha256",body.inputManifestSha256(),"operation",body.operation()),8192).sha256();
    }
    public Optional<Map<String,Object>> replayCreate(UUID input,Create body,String actor){
        requireEnabled();String fingerprint=createFingerprint(input,body,actor);var id=previous(body.requestId(),actor,fingerprint);return id==null?Optional.empty():Optional.of(read(id));
    }
    public Map<String,Object> create(UUID input,Create body,String actor){
        requireEnabled();String requestSha=createFingerprint(input,body,actor);
        var previous=previous(body.requestId(),actor,requestSha);if(previous!=null)return read(previous);
        var binding=catalog.binding(body.operation(),profile);if(binding.profile().isEmpty())throw new InputRejected(503,"specialist_not_configured");
        var verified=inputs.verify(input,null);if(!verified.sha256().equals(body.inputManifestSha256()))throw new InputRejected(409,"input_manifest_changed");
        var descriptor=SpecialistResult.input(input,verified,body.operation(),binding.profile());var encoded=CanonicalJson.encode(descriptor,16384);
        UUID id=tx.execute(status->{lock();var replay=previous(body.requestId(),actor,requestSha);if(replay!=null)return replay;
            var current=inputs.lockForUse(input,verified.sha256());if(!encoded.sha256().equals(CanonicalJson.encode(SpecialistResult.input(input,current,body.operation(),binding.profile()),16384).sha256()))throw new InputRejected(409,"specialist_input_changed");
            registerProfile(binding);
            var duplicates=db.queryForList("SELECT job_id FROM specialist_analysis_jobs WHERE input_sha256=?",encoded.sha256());UUID job;
            SpecialistAdmission.Decision approved=null;
            if(duplicates.isEmpty()){
                room();approved=admission.approve(descriptor,binding);job=UUID.randomUUID();
                int priority=approved==null?0:switch(String.valueOf(approved.summary().get("intent"))){case "verify_vehicle"->2;case "plate_query"->1;default->0;};
                db.update("INSERT INTO specialist_analysis_jobs(job_id,input_id,input_manifest_sha256,frame_id,camera_id,operation,profile,input_sha256,input_descriptor,created_by,routing_snapshot,routing_sha256,selection_priority) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?)",job,input,verified.sha256(),UUID.fromString((String)descriptor.get("frame_id")),descriptor.get("camera_id"),body.operation(),binding.profile(),encoded.sha256(),encoded.payload(),actor,binding.encoded()==null?null:binding.encoded().payload(),binding.encoded()==null?null:binding.encoded().sha256(),priority);
                db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'create_specialist_job','queued',?)",actor,body.requestId());
            }else job=(UUID)duplicates.getFirst().get("job_id");
            request(body.requestId(),actor,requestSha,job);admission.record(approved,body.requestId(),job,descriptor,"create");return job;
        });return read(id);
    }
    private static void lease(String worker,int seconds){if(worker==null||!worker.matches("[A-Za-z0-9_.-]{1,70}")||seconds<60||seconds>600)throw new IllegalArgumentException("Invalid specialist lease");}
    public Optional<Map<String,Object>> claim(String worker,int seconds,String supportedProfile,List<String> operations){return claim(worker,seconds,supportedProfile,operations,List.of());}
    public Optional<Map<String,Object>> claim(String worker,int seconds,String supportedProfile,List<String> operations,List<String> adapters){
        requireEnabled();lease(worker,seconds);
        if(adapters==null||adapters.size()>1||!adapters.isEmpty()&&!adapters.equals(List.of("specialist_sse_v1")))throw new IllegalArgumentException("Invalid specialist adapters");
        boolean routed=!adapters.isEmpty();
        if((supportedProfile==null||supportedProfile.isEmpty())&&!routed||supportedProfile!=null&&!supportedProfile.isEmpty()&&!supportedProfile.matches("[A-Za-z0-9_.-]{1,80}")||operations==null||operations.isEmpty()||operations.size()>2||operations.stream().anyMatch(Objects::isNull)||!SpecialistResult.OPERATIONS.containsAll(operations))throw new IllegalArgumentException("Invalid specialist capabilities");
        return tx.execute(status->{lock();if(!claims())return Optional.empty();
            db.update("UPDATE specialist_analysis_jobs SET state=CASE WHEN attempts+1>=? THEN 'failed' ELSE 'queued' END,attempts=attempts+1,revision=revision+1,last_error_code='job_timeout',lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(LEAST(300,5*power(2,LEAST(attempts,10))) * interval '1 second'),updated_at=clock_timestamp() WHERE state='active' AND lease_expires_at<=clock_timestamp()",policy.maxAttempts());
            if(db.queryForObject("SELECT count(*) FROM specialist_analysis_jobs WHERE state='active'",Long.class)>=policy.activeLimit())return Optional.empty();
            var cameras=new TreeSet<>(search.readableCameras());if(cameras.isEmpty())return Optional.empty();var args=new ArrayList<Object>();args.add(policy.maxAttempts());args.add(supportedProfile);args.add(routed);args.addAll(cameras);args.addAll(new TreeSet<>(operations));args.add(worker);args.add(seconds);
            var rows=db.queryForList("""
                WITH candidate AS(SELECT job_id FROM specialist_analysis_jobs WHERE state='queued' AND attempts<? AND ((routing_snapshot IS NULL AND profile=?) OR (? AND routing_snapshot->'definition'->>'adapter'='specialist_sse_v1')) AND camera_id IN (%s) AND operation IN (%s) AND next_attempt_at<=clock_timestamp() ORDER BY selection_priority DESC,next_attempt_at,created_at,job_id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE specialist_analysis_jobs j SET state='active',revision=revision+1,lease_owner=?,lease_generation=lease_generation+1,lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                FROM candidate WHERE j.job_id=candidate.job_id RETURNING j.*,j.input_descriptor::text AS descriptor_text,j.routing_snapshot::text AS routing_text
                """.formatted(String.join(",",Collections.nCopies(cameras.size(),"?")),String.join(",",Collections.nCopies(new TreeSet<>(operations).size(),"?"))),args.toArray());
            if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
            try{verifyInput(row,false,false);}catch(InputRejected invalid){db.update("UPDATE specialist_analysis_jobs SET state='failed',attempts=attempts+1,revision=revision+1,last_error_code='specialist_input_changed',lease_owner=NULL,lease_expires_at=NULL,updated_at=clock_timestamp() WHERE job_id=?",row.get("job_id"));return Optional.empty();}
            var value=new LinkedHashMap<String,Object>();value.put("jobId",row.get("job_id").toString());value.put("generation",row.get("lease_generation"));value.put("inputSha256",row.get("input_sha256"));value.put("descriptor",descriptor(row));value.put("expiresAt",((Timestamp)row.get("lease_expires_at")).toInstant().toString());
            var route=route(row);if(route!=null){value.put("routing",route);value.put("routingSha256",row.get("routing_sha256"));}return Optional.of(value);
        });
    }
    public boolean renew(UUID job,String worker,long generation,int seconds){requireEnabled();lease(worker,seconds);if(job==null||generation<1)throw new IllegalArgumentException("Invalid specialist generation");return db.update("UPDATE specialist_analysis_jobs SET lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()",seconds,job,worker,generation)==1;}
    public boolean fail(UUID job,String worker,long generation,String code){
        requireEnabled();lease(worker,60);if(job==null||generation<1||code==null||!FAILURES.contains(code))throw new IllegalArgumentException("Invalid specialist failure");
        return tx.execute(status->{var rows=db.queryForList("SELECT attempts FROM specialist_analysis_jobs WHERE job_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp() FOR UPDATE",job,worker,generation);if(rows.isEmpty())return false;
            int count=((Number)rows.getFirst().get("attempts")).intValue()+1;boolean permanent=Set.of("specialist_input_changed","invalid_specialist_result").contains(code)||count>=policy.maxAttempts();db.update("UPDATE specialist_analysis_jobs SET state=?,attempts=?,revision=revision+1,last_error_code=?,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE job_id=?",permanent?"failed":"queued",count,code,policy.retryDelaySeconds(count),job);return true;});
    }
    private Map<String,Object> row(UUID job,boolean locked){var rows=db.queryForList("SELECT *,input_descriptor::text AS descriptor_text,routing_snapshot::text AS routing_text,lease_expires_at>clock_timestamp() AS alive,next_attempt_at>clock_timestamp() AS retry_wait FROM specialist_analysis_jobs WHERE job_id=?"+(locked?" FOR UPDATE":""),job);if(rows.size()!=1||!search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"specialist_job_unavailable");return rows.getFirst();}
    private void registerProfile(ProcessingCatalog.Binding binding){
        if(binding.route()==null)return;var definition=SpecialistResult.object(binding.route().get("definition"));var encoded=CanonicalJson.encode(definition,16384);
        db.update("INSERT INTO processing_model_profiles(profile,definition,definition_sha256) VALUES(?,?::jsonb,?) ON CONFLICT(profile) DO NOTHING",binding.profile(),encoded.payload(),encoded.sha256());
        if(!encoded.sha256().equals(db.queryForObject("SELECT definition_sha256 FROM processing_model_profiles WHERE profile=?",String.class,binding.profile())))throw new InputRejected(409,"processing_profile_conflict");
    }
    private Map<String,Object> route(Map<String,Object> row){
        if(row.get("routing_sha256")==null&&row.get("routing_text")==null)return null;
        try{
            var route=SpecialistResult.object(JSON.readValue((String)row.get("routing_text"),Object.class));SpecialistResult.keys(route,"schema_version","catalog_revision","catalog_sha256","definition");
            if(!"specialist-route-v1".equals(route.get("schema_version"))||!(route.get("catalog_sha256") instanceof String sha)||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            SpecialistResult.integer(route.get("catalog_revision"),1,9007199254740991L);
            var definition=ProcessingCatalog.profile(SpecialistResult.object(route.get("definition")));
            if(!definition.get("profile").equals(row.get("profile"))||!((List<?>)definition.get("capabilities")).contains(row.get("operation"))||!CanonicalJson.encode(route,32768).sha256().equals(row.get("routing_sha256")))throw new IllegalArgumentException();
            return route;
        }catch(Exception invalid){throw new InputRejected(409,"specialist_input_changed");}
    }
    private Map<String,Object> owned(UUID job,String worker,long generation,boolean locked){lease(worker,60);if(generation<1)throw new IllegalArgumentException("Invalid specialist generation");var row=row(job,locked);if(!worker.equals(row.get("lease_owner"))||generation!=((Number)row.get("lease_generation")).longValue()||!("completed".equals(row.get("state"))||"active".equals(row.get("state"))&&Boolean.TRUE.equals(row.get("alive"))))throw new InputRejected(409,"specialist_lease_expired");return row;}
    private Map<String,Object> descriptor(Map<String,Object> row){try{return SpecialistResult.object(JSON.readValue((String)row.get("descriptor_text"),Object.class));}catch(RuntimeException invalid){throw new InputRejected(409,"specialist_input_changed");}}
    private AnalysisInputs.Verified verifyInput(Map<String,Object> row,boolean pixels,boolean locked){
        UUID id=(UUID)row.get("input_id");String expected=(String)row.get("input_manifest_sha256");var verified=locked?inputs.lockForUse(id,expected):pixels?inputs.verify(id,null):inputs.metadata(id);
        var actual=SpecialistResult.input(id,verified,(String)row.get("operation"),(String)row.get("profile"));var encoded=CanonicalJson.encode(actual,16384);
        if(!expected.equals(verified.sha256())||!encoded.sha256().equals(row.get("input_sha256"))||!encoded.sha256().equals(CanonicalJson.encode(descriptor(row),16384).sha256())||!actual.get("camera_id").equals(row.get("camera_id"))||!actual.get("frame_id").equals(row.get("frame_id").toString()))throw new InputRejected(409,"specialist_input_changed");route(row);return verified;
    }
    public EvidenceFiles.FrameFile image(UUID job,String worker,long generation){requireEnabled();var row=owned(job,worker,generation,false);verifyInput(row,false,false);var image=inputs.verify((UUID)row.get("input_id"),(UUID)row.get("frame_id")).image();owned(job,worker,generation,false);return image;}
    public Map<String,Object> complete(Complete body){
        requireEnabled();if(body==null||body.jobId()==null||body.inputSha256()==null||!body.inputSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid specialist completion");var initial=owned(body.jobId(),body.workerId(),body.generation(),false);if(!body.inputSha256().equals(initial.get("input_sha256")))throw new InputRejected(409,"specialist_input_changed");
        verifyInput(initial,true,false);var result=SpecialistResult.validate(body.jobId(),body.inputSha256(),descriptor(initial),body.result());
        tx.executeWithoutResult(status->{var current=owned(body.jobId(),body.workerId(),body.generation(),true);verifyInput(current,false,true);
            if("completed".equals(current.get("state"))){if(!result.sha256().equals(current.get("result_sha256")))throw new InputRejected(409,"specialist_result_conflict");storedResult(current);return;}
            db.update("INSERT INTO specialist_analysis_results(job_id,result,result_sha256) VALUES(?,?::jsonb,?)",body.jobId(),result.payload(),result.sha256());db.update("UPDATE specialist_analysis_jobs SET state='completed',result_sha256=?,last_error_code=NULL,revision=revision+1,lease_expires_at=NULL,updated_at=clock_timestamp() WHERE job_id=?",result.sha256(),body.jobId());db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'complete_specialist_job','completed',?)",body.workerId(),body.jobId());
        });return Map.of("completed",true,"jobId",body.jobId().toString(),"inputSha256",body.inputSha256(),"resultSha256",result.sha256());
    }
    public Map<String,Object> retry(UUID job,Retry body,String actor){
        requireEnabled();if(body==null||body.requestId()==null||body.revision()<1||actor==null||actor.isBlank()||actor.length()>100)throw new IllegalArgumentException("Invalid specialist retry");String sha=CanonicalJson.encode(Map.of("action","retry","job_id",job.toString(),"revision",body.revision()),8192).sha256();
        UUID existing=previous(body.requestId(),actor,sha);if(existing!=null){if(!existing.equals(job))throw new InputRejected(409,"specialist_request_conflict");return read(job);}
        var snapshot=catalog.current();
        if(snapshot!=null)catalog.binding(snapshot,(String)row(job,false).get("operation"),profile);
        tx.executeWithoutResult(status->{lock();var replay=previous(body.requestId(),actor,sha);if(replay!=null){if(!replay.equals(job))throw new InputRejected(409,"specialist_request_conflict");return;}
            var row=row(job,true);verifyInput(row,false,true);if(!"failed".equals(row.get("state"))||((Number)row.get("revision")).longValue()!=body.revision())throw new InputRejected(409,"specialist_revision_conflict");
            var binding=new ProcessingCatalog.Binding((String)row.get("profile"),null,null,catalog.selectionPolicy(snapshot),snapshot==null?0:snapshot.revision());var approved=admission.approve(descriptor(row),binding);
            room();db.update("UPDATE specialist_analysis_jobs SET state='queued',attempts=0,last_error_code=NULL,revision=revision+1,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE job_id=?",job);request(body.requestId(),actor,sha,job);admission.record(approved,body.requestId(),job,descriptor(row),"retry");db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'retry_specialist_job','queued',?)",actor,body.requestId());});return read(job);
    }
    public Map<String,Object> read(UUID job){requireEnabled();var row=row(job,false);verifyInput(row,false,false);return summary(row,claims());}
    public Map<String,Object> list(UUID input,int limit,UUID after){
        requireEnabled();inputs.metadata(input);if(limit<1||limit>50)throw new IllegalArgumentException("Invalid specialist page");
        var rows=db.queryForList("SELECT *,input_descriptor::text AS descriptor_text,routing_snapshot::text AS routing_text,lease_expires_at>clock_timestamp() AS alive,next_attempt_at>clock_timestamp() AS retry_wait FROM specialist_analysis_jobs WHERE input_id=? AND (?::uuid IS NULL OR job_id>?) ORDER BY job_id LIMIT ?",input,after,after,limit+1);boolean more=rows.size()>limit,claims=claims();var page=rows.subList(0,Math.min(limit,rows.size()));var result=new LinkedHashMap<String,Object>();result.put("items",page.stream().map(row->{verifyInput(row,false,false);return summary(row,claims);}).toList());result.put("has_more",more);result.put("next_cursor",more?page.getLast().get("job_id").toString():null);return result;
    }
    public Map<String,Object> result(UUID job){
        requireEnabled();var row=row(job,false);var input=verifyInput(row,true,false);var response=storedResult(row);
        response.put("result_source",SpecialistResultSource.describe(row,input));verifyInput(row,false,false);return response;
    }
    public Map<String,Object> review(UUID job,PlateEventResults.Review body,String actor){
        requireEnabled();if(body==null||actor==null||actor.isBlank()||actor.length()>100)throw new InputRejected(422,"invalid_plate_review");
        var initial=row(job,false);if(!"revoke".equals(body.decision()))verifyInput(initial,true,false);
        var stored=storedResult(initial);var result=SpecialistResult.object(stored.get("result"));
        return tx.execute(status->{lock();var current=row(job,true);
            if(!"revoke".equals(body.decision()))verifyInput(current,false,true);
            if(!Objects.equals(current.get("result_sha256"),stored.get("result_sha256")))throw new InputRejected(409,"specialist_result_changed");
            return events.review(job,current,result,body,actor);
        });
    }
    private Map<String,Object> storedResult(Map<String,Object> row){
        UUID job=(UUID)row.get("job_id");if(!"completed".equals(row.get("state")))throw new InputRejected(404,"specialist_result_unavailable");var rows=db.queryForList("SELECT result::text AS payload,result_sha256 FROM specialist_analysis_results WHERE job_id=?",job);if(rows.size()!=1)throw new InputRejected(409,"specialist_result_changed");var saved=rows.getFirst();Map<String,Object> value;CanonicalJson.Encoded encoded;
        try{value=SpecialistResult.object(JSON.readValue((String)saved.get("payload"),Object.class));encoded=SpecialistResult.validate(job,(String)row.get("input_sha256"),descriptor(row),value);if(!encoded.sha256().equals(saved.get("result_sha256"))||!encoded.sha256().equals(row.get("result_sha256")))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new InputRejected(409,"specialist_result_changed");}
        var response=new LinkedHashMap<String,Object>(Map.of("job_id",job.toString(),"result_revision",1,"result",value,"result_payload",encoded.payload(),"result_sha256",saved.get("result_sha256"),"review_state","unreviewed","score_calibrated",false,"search_indexed",false,"engine_provenance","provider_declared"));
        if("license_plate".equals(row.get("operation"))&&"recognized".equals(value.get("outcome"))){
            response.put("plate_review",events.reviewState(job));
            var target=events.reviewTarget(job,row,value);response.put("plate_review_available",target.isPresent());target.ifPresent(valueTarget->response.put("plate_review_target",valueTarget));
        }
        return response;
    }
    static Map<String,Object> summary(Map<String,Object> row,boolean claims){var value=new LinkedHashMap<String,Object>();for(String key:List.of("job_id","input_id","input_manifest_sha256","frame_id","camera_id","operation","profile","input_sha256","state","attempts","revision","last_error_code","result_sha256","created_by","created_at","updated_at","next_attempt_at")){Object raw=row.get(key);value.put(key,raw instanceof UUID id?id.toString():raw instanceof Timestamp time?time.toInstant().toString():raw);}
        String state=(String)row.get("state");value.put("claims_enabled",claims);value.put("dispatch_state","queued".equals(state)?!claims?"paused":Boolean.TRUE.equals(row.get("retry_wait"))?"retry_wait":"waiting":"active".equals(state)?Boolean.TRUE.equals(row.get("alive"))?"running":"lease_expired":state);return value;}
}
