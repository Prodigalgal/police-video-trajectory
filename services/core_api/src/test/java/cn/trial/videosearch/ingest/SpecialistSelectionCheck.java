package cn.trial.videosearch.ingest;

import static cn.trial.videosearch.support.CatalogTestSources.none;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Private state fixtures over actual saved frames; not selection/recognition truth. */
@SuppressWarnings("unchecked")
public final class SpecialistSelectionCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String POLICY="b".repeat(64);
    private static void check(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
    private static void reject(Runnable action,String code){try{action.run();throw new IllegalStateException("missing "+code);}catch(InputRejected failure){check(code.equals(failure.code),"expected "+code+" got "+failure.code);}}
    private static Map<String,Object> profile(String id){
        return Map.of("profile",id,"adapter","specialist_sse_v1","capabilities",List.of("license_plate"),"base_url","https://localhost:18081",
            "token_file","specialist.token","ca_file","tls.ca.crt","model_id",id,"contract","specialist-result-v1","streaming",true,"timeout_seconds",360);
    }
    private static Map<String,Object> catalog(int revision,String active,boolean enabled){
        return Map.of("schema_version","processing-catalog-v1","revision",revision,
            "modules",Map.of("license_plate",Map.of("enabled",enabled,"profile",active),
                "specialist_selection",Map.of("enabled",true,"plugin","rules_v1","mode","select","policy_sha256",POLICY),"face_quality",Map.of("enabled",false)),
            "profiles",List.of(profile("route-a"),profile("route-b"),profile("route-c")));
    }
    private static void publish(Path path,Map<String,Object> value)throws Exception{
        Path temporary=path.resolveSibling("catalog.new");Files.writeString(temporary,JSON.writeValueAsString(value));Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static SpecialistJobs service(JdbcTemplate db,DataSourceTransactionManager manager,AnalysisInputs inputs,SearchOrchestrator search,Path catalog,int budget){
        return new SpecialistJobs(db,inputs,search,manager,true,"",100,1,3,new ProcessingCatalog(catalog.toString(), none()),new SpecialistAdmission(db,true,POLICY,300,budget,new PlateEventResults(db,100)),new PlateEventResults(db,3));
    }
    static Map<String,Object> selection(Map<String,Object> source,Map<String,Object> plan,Map<String,Object> target,String state)throws Exception{
        String frame=source.get("frame_id").toString(),sha=(String)source.get("payload_sha256"),id=(String)target.get("target_id");
        var vehicle=(List<?>)target.get("bbox_xyxy");int x=((Number)vehicle.get(0)).intValue(),y=((Number)vehicle.get(1)).intValue(),r=((Number)vehicle.get(2)).intValue(),b=((Number)vehicle.get(3)).intValue();
        var candidate=new LinkedHashMap<String,Object>(Map.of("target_id",id,"track_id",target.get("track_id"),"decision",state,"reasons",List.of("first_usable_candidate"),
            "representative",Map.of("frame_id",frame,"jpeg_sha256",sha,"rank",.8,"relative_media_ms",source.get("relative_media_ms"))));
        candidate.put("business_state",state.equals("selected")?"need_read":"waiting_evidence");candidate.put("intent","verify_vehicle");
        candidate.put("event_id",SpecialistAdmission.eventId(source.get("camera_id").toString(),source.get("scene_run_id").toString(),((Number)source.get("media_epoch")).longValue(),(String)target.get("track_id")));
        candidate.put("plate_evidence",Map.of("schema_version","plate-evidence-v1","adapter","opencv_plate_v1","model_sha256","c".repeat(64),"association","unique_spatial_containment","frame_id",frame,"jpeg_sha256",sha,"bbox_xyxy",List.of(x,y,Math.max(x+1,r),Math.max(y+1,b)),"localization_score",.9));
        candidate.putAll(Map.of("passage_evidence","explicit_request","quality_scope","localized_plate_not_character_correctness","native_width_px",Math.max(1,r-x),"native_height_px",Math.max(1,b-y),"readability_rank",.8));
        var value=new LinkedHashMap<String,Object>();value.putAll(Map.of("schema_version","specialist-selection-v2","policy_sha256",POLICY,"mode","select","state","complete",
            "frame_id",frame,"jpeg_sha256",sha,"camera_id",source.get("camera_id"),"scene_run_id",source.get("scene_run_id").toString(),"media_epoch",source.get("media_epoch"),"relative_media_ms",source.get("relative_media_ms")));
        value.put("image_width",plan.get("image_width"));value.put("image_height",plan.get("image_height"));
        value.put("frame_suppression",false);value.put("semantic_modules_changed",false);value.put("original_targets_changed",false);
        value.put("modules",List.of(Map.of("module","license_plate","decision",state,"business_state",state.equals("selected")?"need_read":"waiting_evidence","reasons",List.of(state.equals("selected")?"readable_plate_candidate":"no_new_specialist_candidate"),"selected_target_ids",state.equals("selected")?List.of(id):List.of(),"targets",List.of(candidate)),
            Map.of("module","face_quality","decision","deferred","reasons",List.of("face_detection_evidence_required"),"selected_target_ids",List.of(),"identity_matching",false,"operation_available",false)));
        return value;
    }
    static void apply(JdbcTemplate db,UUID frame,Map<String,Object> plan,Map<String,Object> selection){
        var metrics=new LinkedHashMap<String,Object>();if(plan.get("selection_metrics") instanceof Map<?,?> old)metrics.putAll((Map<String,Object>)old);
        metrics.put("specialist_selection",selection);plan.put("selection_metrics",metrics);db.update("UPDATE capture_outbox SET plan=?::jsonb WHERE frame_id=?",JSON.writeValueAsString(plan),frame);
    }
    private static void reviewChecks(JdbcTemplate db,DataSourceTransactionManager manager,AnalysisInputs inputs,SearchOrchestrator search,
        SpecialistJobs jobs,UUID job,Map<String,Object> input,List<String> checks){
        var transaction=new TransactionTemplate(manager);
        transaction.executeWithoutResult(status->{
            try{
                // Only state-contract fields are substituted, inside a rollback-only transaction. No OCR quality claim.
                var record=db.queryForList("SELECT *,input_descriptor::text AS descriptor_text FROM specialist_analysis_jobs WHERE job_id=?",job).getFirst();
                var admission=SpecialistResult.object(JSON.readValue(db.queryForObject("SELECT decision::text FROM specialist_analysis_admissions WHERE job_id=? ORDER BY created_at LIMIT 1",String.class,job),Object.class));
                var descriptor=SpecialistResult.object(JSON.readValue((String)record.get("descriptor_text"),Object.class));
                var ledger=new PlateEventResults(db,3);
                db.execute("SAVEPOINT priority_check");
                db.update("UPDATE specialist_analysis_jobs SET state='queued',result_sha256=NULL WHERE job_id=?",job);
                var prioritized=jobs.claim("priority-check",120,"protocol-test-v1",List.of("license_plate","text_ocr"),List.of("specialist_sse_v1")).orElseThrow();
                check(job.toString().equals(prioritized.get("jobId")),"explicit vehicle check did not precede older background work");
                db.execute("ROLLBACK TO SAVEPOINT priority_check");
                checks.add("explicit_vehicle_check_precedes_older_background_work");
                check("unreadable".equals(ledger.assess(admission,descriptor).get("business_state")),"event submission limit was ignored");
                checks.add("event_submission_limit_is_separate_from_candidate_recommendations");
                db.update("UPDATE specialist_analysis_jobs SET state='queued',result_sha256=NULL WHERE job_id=?",job);
                var automatic=new LinkedHashMap<>(admission);automatic.put("intent","collect_vehicle_passages");
                check("waiting_result".equals(ledger.assess(automatic,descriptor).get("business_state")),"pending event was duplicated");
                db.update("UPDATE specialist_analysis_jobs SET state='completed',result_sha256=? WHERE job_id=?",record.get("result_sha256"),job);
                var artifact=jobs.result(job);var recognized=new LinkedHashMap<>(SpecialistResult.object(artifact.get("result")));
                recognized.put("outcome","recognized");recognized.put("engine",Map.of("name","review-state-contract-fixture","version","not-recognition-evidence"));
                recognized.put("regions",List.of(Map.of("index",0,"text","状态契约夹具","bbox_xyxy",admission.get("plate_bbox_xyxy"),"score",.5)));
                var encoded=SpecialistResult.validate(job,(String)record.get("input_sha256"),descriptor,recognized);
                db.update("UPDATE specialist_analysis_results SET result=?::jsonb,result_sha256=? WHERE job_id=?",encoded.payload(),encoded.sha256(),job);
                db.update("UPDATE specialist_analysis_jobs SET result_sha256=? WHERE job_id=?",encoded.sha256(),job);
                check("pending_review".equals(ledger.assess(automatic,descriptor).get("business_state")),"unreviewed text was treated as reliable");
                reject(()->jobs.review(job,new PlateEventResults.Review(UUID.randomUUID(),encoded.sha256(),0,"confirm",0,false),"reader"),"invalid_plate_review");
                reject(()->jobs.review(job,new PlateEventResults.Review(UUID.randomUUID(),encoded.sha256(),1,"confirm",0,true),"reader"),"invalid_plate_review");
                var request=new PlateEventResults.Review(UUID.randomUUID(),encoded.sha256(),0,"confirm",0,true);
                var confirmed=jobs.review(job,request,"reader");var replayed=jobs.review(job,request,"reader");
                check(cn.trial.videosearch.support.CanonicalJson.encode(confirmed,8192).sha256().equals(cn.trial.videosearch.support.CanonicalJson.encode(replayed,8192).sha256())
                    &&((Number)ledger.reviewState(job).get("revision")).longValue()==1,"review replay consumed revision");
                reject(()->jobs.review(job,request,"other-reader"),"plate_review_conflict");
                check("confirmed".equals(confirmed.get("state")),"review was not persisted");
                var reusable=ledger.assess(automatic,descriptor);check("reused".equals(reusable.get("decision")),"confirmed same-event result was not referenced");
                check(!reusable.containsKey("plate_text")&&((Map<?,?>)reusable.get("reuse_reference")).get("job_id").equals(job.toString()),"reference assigned text to a frame");
                var later=new LinkedHashMap<>(automatic);later.put("relative_media_ms",((Number)admission.get("relative_media_ms")).longValue()+11000);
                check(!"reused".equals(ledger.assess(later,descriptor).get("decision")),"expired event reused result");
                var wrongCamera=new LinkedHashMap<>(descriptor);wrongCamera.put("camera_id","recording-04".equals(descriptor.get("camera_id"))?"recording-01":"recording-04");
                check(!"reused".equals(ledger.assess(automatic,wrongCamera).get("decision")),"result crossed camera");
                UUID anchor=UUID.fromString((String)input.get("anchor_observation_id"));
                db.update("UPDATE observations SET deleted=true WHERE observation_id=?",anchor);
                check(!"reused".equals(ledger.assess(automatic,descriptor).get("decision")),"withdrawn source reused result");
                db.update("UPDATE observations SET deleted=false,record_version=record_version+1 WHERE observation_id=?",anchor);
                check(!"reused".equals(ledger.assess(automatic,descriptor).get("decision")),"revised source reused result");
                db.update("UPDATE observations SET record_version=record_version-1 WHERE observation_id=?",anchor);
                db.update("UPDATE capture_outbox SET payload_sha256=? WHERE frame_id=?","f".repeat(64),record.get("frame_id"));
                check(!"reused".equals(ledger.assess(automatic,descriptor).get("decision")),"changed source digest reused result");
                db.update("UPDATE capture_outbox SET payload_sha256=? WHERE frame_id=?",descriptor.get("evidence_sha256"),record.get("frame_id"));
                var ui=Map.of("input",input,"job",jobs.read(job),"result",jobs.result(job),"selection",reusable,
                    "fixture_scope","review_state_contract_only_not_actual_recognition");
                jobs.review(job,new PlateEventResults.Review(UUID.randomUUID(),encoded.sha256(),0,"revoke",1,false),"reader");
                check(!"reused".equals(ledger.assess(automatic,descriptor).get("decision")),"revoked review still reused");
                reject(()->jobs.review(job,new PlateEventResults.Review(UUID.randomUUID(),encoded.sha256(),0,"confirm",1,true),"reader"),"plate_review_conflict");
                checks.add("pending_unreviewed_and_confirmed_event_states_are_distinct");
                checks.add("plate_region_review_is_explicit_idempotent_and_revision_fenced");
                checks.add("expired_cross_camera_withdrawn_revised_digest_changed_and_revoked_results_do_not_reuse");
                System.out.println("SPECIALIST_REVIEW_UI_FIXTURE="+JSON.writeValueAsString(ui));
            }finally{status.setRollbackOnly();}
        });
        check("unreadable".equals(((Map<?,?>)jobs.result(job).get("result")).get("outcome")),"contract fixture escaped rollback");
        checks.add("review_contract_fixture_is_rolled_back_without_semantic_writes");
    }
    public static void run(JdbcTemplate db,DataSourceTransactionManager manager,AnalysisInputs inputs,SearchOrchestrator search,List<Map<String,Object>> saved,Path root,List<String> checks)throws Exception{
        Map<String,Object> chosen=null,source=null,plan=null,target=null;
        for(var candidate:saved){
            var row=db.queryForList("SELECT frame_id,camera_id,scene_run_id,media_epoch,relative_media_ms,payload_sha256,plan::text AS plan_text FROM capture_outbox WHERE frame_id=?",UUID.fromString((String)candidate.get("anchor_frame_id"))).getFirst();
            var parsed=SpecialistResult.object(JSON.readValue((String)row.get("plan_text"),Object.class));
            for(Object raw:(List<?>)parsed.get("targets")){var detected=SpecialistResult.object(raw);if("vehicle".equals(detected.get("object_type"))&&Set.of("car","motorcycle","bus","truck").contains(detected.get("class_name"))){chosen=candidate;source=row;plan=parsed;target=detected;break;}}
            if(chosen!=null)break;
        }
        check(chosen!=null,"an actual detected vehicle frame is required");
        final var input=chosen;final UUID inputId=UUID.fromString((String)input.get("input_id")),frame=UUID.fromString((String)input.get("anchor_frame_id"));
        final String original=(String)source.get("plan_text"),manifest=(String)input.get("manifest_sha256");
        Path file=root.resolve("catalog.json");publish(file,catalog(1,"route-a",true));var jobs=service(db,manager,inputs,search,file,4);
        var ui=new ArrayList<Map<String,Object>>();
        try{
            check("unavailable".equals(jobs.selection(inputId).get("decision")),"legacy frame was selected without evidence");
            reject(()->jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader"),"specialist_selection_unavailable");
            for(String state:List.of("skipped","deferred")){
                apply(db,frame,plan,selection(source,plan,target,state));check(state.equals(jobs.selection(inputId).get("decision")),"selection state collapsed");
                ui.add(Map.of("input",input,"selection",jobs.selection(inputId)));
                reject(()->jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader"),"specialist_not_selected");
            }
            checks.add("missing_skipped_and_deferred_inputs_do_not_enqueue");
            var selected=selection(source,plan,target,"selected");selected.put("jpeg_sha256","f".repeat(64));apply(db,frame,plan,selected);
            reject(()->jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader"),"specialist_selection_unavailable");
            selected=selection(source,plan,target,"selected");apply(db,frame,plan,selected);ui.add(Map.of("input",input,"selection",jobs.selection(inputId)));
            checks.add("selection_is_bound_to_real_frame_digest_and_detected_vehicle");
            var firstRequest=new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate");var a=jobs.create(inputId,firstRequest,"reader");UUID aId=UUID.fromString((String)a.get("job_id"));
            check(a.equals(jobs.create(inputId,firstRequest,"reader")),"admission replay changed");
            db.update("UPDATE processing_controls SET enabled=true WHERE control_key='analysis_claims'");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);while(!Files.exists(root.resolve("route-started.json"))){check(System.nanoTime()<until,"route-a did not begin");Thread.sleep(50);}
            publish(file,catalog(2,"route-b",true));var b=jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader");UUID bId=UUID.fromString((String)b.get("job_id"));
            check(!aId.equals(bId)&&"route-a".equals(a.get("profile"))&&"route-b".equals(b.get("profile")),"model switch overwrote job binding");
            checks.add("model_switch_freezes_inflight_a_and_new_b");
            publish(file,catalog(3,"route-c",true));var contenders=new ArrayList<Map<String,Object>>();
            String evidence=SpecialistResult.input(inputId,inputs.metadata(inputId),"license_plate","route-c").get("evidence_sha256").toString();
            for(int index=0;index<8;index++)contenders.add(inputs.create(new AnalysisInputs.Create(UUID.randomUUID(),UUID.fromString((String)input.get("anchor_observation_id")),((Number)input.get("anchor_record_version")).longValue(),evidence,"single_frame",null),"budget-reader-"+index));
            var ids=new ArrayList<UUID>();var pool=Executors.newFixedThreadPool(8);var barrier=new CyclicBarrier(8);var tasks=new ArrayList<Future<UUID>>();
            try{
                for(int index=0;index<8;index++){final int actor=index;var candidate=contenders.get(index);tasks.add(pool.submit(()->{
                    barrier.await(10,TimeUnit.SECONDS);var independent=service(db,manager,inputs,search,file,4);
                    try{return UUID.fromString((String)independent.create(UUID.fromString((String)candidate.get("input_id")),new SpecialistJobs.Create(UUID.randomUUID(),(String)candidate.get("manifest_sha256"),"license_plate"),"budget-reader-"+actor).get("job_id"));}
                    catch(InputRejected limited){check(limited.code.equals("specialist_selection_budget"),"unexpected contender rejection");return null;}
                }));}
                for(var task:tasks){UUID id=task.get(40,TimeUnit.SECONDS);if(id!=null)ids.add(id);}
            }finally{pool.shutdownNow();}
            check(ids.size()==2&&db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions",Long.class)==4,"camera quota was not atomic");
            checks.add("eight_instances_share_atomic_camera_admission_budget");
            UUID retryId=ids.getFirst();db.update("UPDATE specialist_analysis_jobs SET state='failed',attempts=1,revision=revision+1 WHERE job_id=? AND state='queued'",retryId);
            long revision=((Number)jobs.read(retryId).get("revision")).longValue();var retry=new SpecialistJobs.Retry(UUID.randomUUID(),revision);
            reject(()->jobs.retry(retryId,retry,"reader"),"specialist_selection_budget");
            var resumed=service(db,manager,inputs,search,file,5);String routeSha=db.queryForObject("SELECT routing_sha256 FROM specialist_analysis_jobs WHERE job_id=?",String.class,retryId);
            resumed.retry(retryId,retry,"reader");resumed.retry(retryId,retry,"reader");
            check(routeSha.equals(db.queryForObject("SELECT routing_sha256 FROM specialist_analysis_jobs WHERE job_id=?",String.class,retryId))&&db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions",Long.class)==5,"retry rewrote route or admission");
            checks.add("retry_rechecks_budget_is_idempotent_and_keeps_frozen_route");
            publish(file,catalog(4,"route-c",false));reject(()->jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader"),"specialist_module_disabled");
            reject(()->jobs.retry(retryId,new SpecialistJobs.Retry(UUID.randomUUID(),1),"reader"),"specialist_module_disabled");
            checks.add("disabled_module_blocks_new_retry_but_not_receipt_replay");
            Files.writeString(file,"{");reject(()->jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader"),"processing_catalog_unavailable");
            resumed.retry(retryId,retry,"reader");checks.add("retry_receipt_replay_survives_broken_catalog");
            Files.writeString(root.resolve("route-switch-ready.json"),"{}");
            var completed=new ArrayList<UUID>();completed.add(aId);completed.add(bId);completed.addAll(ids);
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            for(UUID id:completed)while(!"completed".equals(jobs.read(id).get("state"))){check(System.nanoTime()<until,"routed jobs did not drain");Thread.sleep(100);}
            check(!jobs.claim("legacy-only",120,"route-a",List.of("license_plate")).isPresent(),"legacy consumer took a routed job");
            for(UUID id:completed)check("unreviewed".equals(jobs.result(id).get("review_state")),"route result promoted to fact");
            checks.add("disabled_module_and_broken_catalog_do_not_interrupt_frozen_jobs");
            var changed=catalog(5,"route-a",true);
            var mutable=new LinkedHashMap<String,Object>(profile("route-a"));mutable.put("base_url","https://changed.invalid");
            var definitions=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)changed.get("profiles"));definitions.set(0,mutable);
            var replacement=new LinkedHashMap<String,Object>(changed);replacement.put("profiles",definitions);publish(file,replacement);
            var restarted=service(db,manager,inputs,search,file,5);reject(()->restarted.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader"),"processing_profile_conflict");
            checks.add("profile_definition_immutability_survives_core_restart");
            publish(file,catalog(6,"route-a",true));long count=db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions",Long.class);
            check(aId.toString().equals(jobs.create(inputId,new SpecialistJobs.Create(UUID.randomUUID(),manifest,"license_plate"),"reader").get("job_id"))&&count==db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions",Long.class),"existing result consumed admission");
            checks.add("existing_result_reuse_does_not_consume_model_budget");
            reviewChecks(db,manager,inputs,search,jobs,aId,input,checks);
            System.out.println("SPECIALIST_SELECTION_UI_FIXTURE="+JSON.writeValueAsString(ui));
        }finally{db.update("UPDATE capture_outbox SET plan=?::jsonb WHERE frame_id=?",original,frame);db.update("UPDATE processing_controls SET enabled=false WHERE control_key='analysis_claims'");}
    }
}
