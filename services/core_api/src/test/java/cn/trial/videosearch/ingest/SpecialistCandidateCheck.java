package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;

/** Real saved frames; only scheduling/transport states are fault fixtures, never OCR truth. */
@SuppressWarnings("unchecked")
public final class SpecialistCandidateCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String POLICY="b".repeat(64);
    private record Case(Map<String,Object> source,Map<String,Object> plan,Map<String,Object> target,boolean bound){}
    private static void check(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
    private static void reject(Runnable action,String code){try{action.run();throw new IllegalStateException("missing "+code);}catch(InputRejected failure){check(code.equals(failure.code),"expected "+code+" got "+failure.code);}}
    private static SpecialistJobs jobs(JdbcTemplate db,DataSourceTransactionManager manager,AnalysisInputs inputs,SearchOrchestrator search,String profile,int capacity,String catalog){
        var ledger=new PlateEventResults(db,100);
        return new SpecialistJobs(db,inputs,search,manager,true,profile,capacity,1,3,new ProcessingCatalog(catalog),new SpecialistAdmission(db,true,POLICY,300,100,ledger),ledger);
    }
    private static Map<String,Object> row(JdbcTemplate db,UUID id){return db.queryForList("SELECT * FROM specialist_candidates WHERE candidate_id=?",id).getFirst();}
    private static SpecialistCandidates.Lease claim(JdbcTemplate db,SpecialistCandidates queue,UUID id,String owner){
        db.update("UPDATE specialist_candidates SET next_attempt_at=CASE WHEN candidate_id=? THEN clock_timestamp()-interval '1 second' ELSE clock_timestamp()+interval '300 seconds' END WHERE state NOT IN ('active','submitted','reused','invalid','expired','skipped')",id);
        var lease=queue.claim(owner).orElseThrow();check(id.equals(lease.id()),"wrong candidate claimed");return lease;
    }
    private static List<Case> sources(JdbcTemplate db){
        var output=new ArrayList<Case>();
        for(var source:db.queryForList("SELECT c.*,c.plan::text AS plan_text FROM capture_outbox c JOIN frames f ON f.frame_id=c.frame_id WHERE f.analysis_state='completed' ORDER BY c.camera_id,c.frame_id")){
            var plan=SpecialistResult.object(JSON.readValue((String)source.get("plan_text"),Object.class));
            var bound=new HashSet<>(db.queryForList("SELECT t.target_id FROM detected_targets t JOIN observations o ON o.frame_id=t.frame_id AND o.object_ordinal=t.observation_ordinal WHERE t.frame_id=? AND t.object_type='vehicle' AND o.target_type='vehicle' AND NOT o.deleted",UUID.class,source.get("frame_id")));
            var targets=((List<?>)plan.get("targets")).stream().map(SpecialistResult::object).filter(t->"vehicle".equals(t.get("object_type"))&&Set.of("car","truck","bus","motorcycle").contains(t.get("class_name"))).sorted(Comparator.comparing(t->!bound.contains(UUID.fromString((String)t.get("target_id"))))).toList();
            if(!targets.isEmpty())output.add(new Case(source,plan,targets.getFirst(),bound.contains(UUID.fromString((String)targets.getFirst().get("target_id")))));
        }
        output.sort(Comparator.comparing(Case::bound).reversed());return output;
    }
    public static void run(JdbcTemplate db,DataSourceTransactionManager manager,AnalysisInputs inputs,SearchOrchestrator search,Path root,List<String> checks)throws Exception{
        check(db.queryForObject("SELECT current_schema()",String.class).matches("queue_check_[0-9]{8}t[0-9]{6}z_[0-9a-f]{8}"),"candidate faults require an owned private schema");
        var cases=sources(db);check(cases.size()>=2&&cases.getFirst().bound(),"two real vehicle frames and one exact observed target required");cases=new ArrayList<>(cases.subList(0,2));
        var policy=new SpecialistCandidatePolicy(true,1,1,1,30,5,900);
        var queue=new SpecialistCandidates(db,manager,search,policy);var ui=new LinkedHashMap<String,Object>();
        var chosen=cases.getFirst();UUID frame=(UUID)chosen.source().get("frame_id"),id=SpecialistCandidates.request(frame,"candidate");
        Map<String,Object> valid=null;
        long inputsBefore=db.queryForObject("SELECT count(*) FROM analysis_inputs",Long.class),jobsBefore=db.queryForObject("SELECT count(*) FROM specialist_analysis_jobs",Long.class);
        try{
            for(var sample:cases){
                var selected=SpecialistSelectionCheck.selection(sample.source(),sample.plan(),sample.target(),"selected");
                var candidate=SpecialistResult.object(((List<?>)SpecialistResult.object(((List<?>)selected.get("modules")).getFirst()).get("targets")).getFirst());
                candidate.put("intent","collect_vehicle_passages");candidate.put("passage_evidence","moving_observed");
                SpecialistSelectionCheck.apply(db,(UUID)sample.source().get("frame_id"),sample.plan(),selected);
                db.update("UPDATE capture_outbox SET registered_at=clock_timestamp()-interval '2 seconds' WHERE frame_id=?",sample.source().get("frame_id"));
                if(sample==chosen)valid=selected;
            }
            check(queue.scan()==0&&queue.claim("paused-candidate").isEmpty(),"pause allowed candidate production");
            checks.add("candidate_scan_and_claim_respect_global_pause");
            db.update("UPDATE processing_controls SET enabled=true WHERE control_key='analysis_claims'");
            var pool=Executors.newFixedThreadPool(8);var barrier=new CyclicBarrier(8);var scans=new ArrayList<Future<Integer>>();
            try{
                for(int index=0;index<8;index++)scans.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return new SpecialistCandidates(db,manager,search,policy).scan();}));
                int total=0;for(var scan:scans)total+=scan.get(30,TimeUnit.SECONDS);
                check(total==1&&db.queryForObject("SELECT count(*) FROM specialist_candidates",Long.class)==1,"candidate scan exceeded cap or duplicated source");
            }finally{pool.shutdownNow();}
            // Prefer the bound source even when timestamp ties picked the other real frame.
            UUID scanned=db.queryForObject("SELECT candidate_id FROM specialist_candidates",UUID.class);
            if(!id.equals(scanned)){
                db.update("DELETE FROM specialist_candidates WHERE candidate_id=?",scanned);
                db.update("UPDATE capture_outbox SET registered_at=clock_timestamp()-interval '10 seconds' WHERE frame_id=?",frame);
                check(queue.scan()==1,"bound source could not be scanned");
            }
            checks.add("eight_candidate_scanners_share_capacity_and_deduplicate_frames");
            pool=Executors.newFixedThreadPool(8);var claims=new ArrayList<Future<Optional<SpecialistCandidates.Lease>>>();
            try{
                for(int index=0;index<8;index++){final String owner="candidate-instance-"+index;claims.add(pool.submit(()->new SpecialistCandidates(db,manager,search,policy).claim(owner)));}
                var leases=new ArrayList<SpecialistCandidates.Lease>();for(var result:claims)result.get(30,TimeUnit.SECONDS).ifPresent(leases::add);
                check(leases.size()==1,"candidate shared active slot exceeded");var old=leases.getFirst();
                db.update("UPDATE specialist_candidates SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE candidate_id=?",id);
                check(queue.claim("candidate-reclaimer").isEmpty(),"lease retry ignored backoff");
                var fresh=claim(db,queue,id,"candidate-reclaimer");check(fresh.generation()>old.generation(),"lease generation not advanced");
                check(!queue.finish(old,SpecialistCandidateState.FAILED,"late_failure",0,null,true),"old generation overwrote candidate");
                queue.finish(fresh,SpecialistCandidateState.WAITING_CONFIGURATION,"specialist_not_configured",30,null,false);
            }finally{pool.shutdownNow();}
            checks.add("candidate_lease_expiry_backoff_and_old_generation_fencing");
            var unconfigured=jobs(db,manager,inputs,search,"",100,"");var producer=new SpecialistCandidateProducer(queue,inputs,unconfigured,policy);
            db.update("UPDATE frames SET analysis_state='queued' WHERE frame_id=?",frame);
            producer.process(claim(db,queue,id,"source-wait"));check("waiting_source".equals(row(db,id).get("state")),"incomplete main source was submitted");
            ui.put("waiting_source",queue.list(null,null,20,null));db.update("UPDATE frames SET analysis_state='completed' WHERE frame_id=?",frame);
            producer.process(claim(db,queue,id,"configuration-wait"));check("waiting_configuration".equals(row(db,id).get("state")),"missing specialist configuration was submitted");
            check(db.queryForObject("SELECT count(*) FROM analysis_inputs",Long.class)==inputsBefore&&db.queryForObject("SELECT count(*) FROM specialist_analysis_jobs",Long.class)==jobsBefore,"waiting created placeholder work");
            ui.put("waiting_configuration",queue.list(null,null,20,null));checks.add("candidate_waits_for_exact_vehicle_observation_and_configuration_without_placeholder_work");
            db.update("UPDATE specialist_candidates SET state='failed',reason='candidate_submission_unavailable',failures=5,revision=revision+1 WHERE candidate_id=?",id);
            ui.put("failed",queue.list(null,"failed",20,null));long revision=((Number)row(db,id).get("revision")).longValue();
            var recovery=new SpecialistCandidates.Retry(UUID.randomUUID(),revision,"依赖已恢复");
            check(queue.scan()==1,"second real frame was not offered after terminal failure");
            reject(()->queue.retry(id,recovery,"admin"),"candidate_queue_full");
            db.update("UPDATE specialist_candidates SET state='skipped',reason='test_other_source_held',revision=revision+1 WHERE candidate_id<>?",id);
            reject(()->queue.retry(id,new SpecialistCandidates.Retry(UUID.randomUUID(),revision-1,"stale"),"admin"),"candidate_revision_conflict");
            var restored=queue.retry(id,recovery,"admin");var replay=queue.retry(id,recovery,"admin");
            ui.put("recovery",restored);
            check(cn.trial.videosearch.support.CanonicalJson.encode(restored,16384).sha256().equals(cn.trial.videosearch.support.CanonicalJson.encode(replay,16384).sha256()),"recovery replay changed receipt");
            reject(()->queue.retry(id,new SpecialistCandidates.Retry(recovery.requestId(),revision,"changed"),"admin"),"candidate_recovery_conflict");
            checks.add("candidate_recovery_is_capacity_limited_revision_fenced_and_idempotent");
            var changed=new LinkedHashMap<>(valid);changed.put("jpeg_sha256","f".repeat(64));SpecialistSelectionCheck.apply(db,frame,chosen.plan(),changed);
            producer.process(claim(db,queue,id,"source-change"));check("invalid".equals(row(db,id).get("state")),"changed selection source was submitted");
            ui.put("invalid",queue.list(null,"invalid",20,null));SpecialistSelectionCheck.apply(db,frame,chosen.plan(),valid);
            db.update("UPDATE specialist_candidates SET state='ready',expires_at=clock_timestamp()-interval '1 second' WHERE candidate_id=?",id);
            check(queue.claim("expired-candidate").isEmpty()&&"expired".equals(row(db,id).get("state")),"expired candidate was submitted");
            ui.put("expired",queue.list(null,"expired",20,null));checks.add("changed_selection_and_expired_candidates_do_not_submit");
            db.update("UPDATE specialist_candidates SET state='ready',reason='candidate_ready',failures=0,expires_at=clock_timestamp()+interval '900 seconds',revision=revision+1 WHERE candidate_id=?",id);
            var limited=jobs(db,manager,inputs,search,"protocol-test-v1",1,"");new SpecialistCandidateProducer(queue,inputs,limited,policy).process(claim(db,queue,id,"budget-wait"));
            check("waiting_budget".equals(row(db,id).get("state"))&&row(db,id).get("input_id")!=null&&db.queryForObject("SELECT count(*) FROM specialist_analysis_jobs",Long.class)==jobsBefore,"queue budget did not preserve pending input");
            ui.put("waiting_budget",queue.list(null,"waiting_budget",20,null));checks.add("specialist_capacity_wait_preserves_candidate_input_without_extra_job");
            var configured=jobs(db,manager,inputs,search,"protocol-test-v1",100,"");new SpecialistCandidateProducer(queue,inputs,configured,policy).process(claim(db,queue,id,"actual-submit"));
            var submitted=row(db,id);UUID job=(UUID)submitted.get("job_id");check("submitted".equals(submitted.get("state"))&&job!=null,"actual auto submission failed");
            long admissions=db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions",Long.class);
            db.update("UPDATE specialist_candidates SET state='ready',reason='candidate_database_unavailable',job_id=NULL,revision=revision+1 WHERE candidate_id=?",id);
            Path broken=root.resolve("candidate-broken-catalog.json");Files.writeString(broken,"{");
            new SpecialistCandidateProducer(queue,inputs,jobs(db,manager,inputs,search,"protocol-test-v1",100,broken.toString()),policy).process(claim(db,queue,id,"lost-ack-replay"));
            check("submitted".equals(row(db,id).get("state"))&&job.equals(row(db,id).get("job_id"))&&db.queryForObject("SELECT count(*) FROM specialist_analysis_jobs",Long.class)==jobsBefore+1&&db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions",Long.class)==admissions,"lost acknowledgement duplicated work or consulted broken configuration");
            ui.put("submitted",queue.list(null,"submitted",20,null));checks.add("candidate_unknown_ack_replays_original_job_before_broken_catalog_without_new_admission");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(!"completed".equals(configured.read(job).get("state"))){check(System.nanoTime()<deadline,"automatic transport task did not finish");Thread.sleep(100);}
            check("unreadable".equals(SpecialistResult.object(configured.result(job).get("result")).get("outcome")),"transport fixture was promoted to OCR truth");
            checks.add("automatic_candidate_actual_binary_streaming_job_finishes_without_semantic_or_identity_promotion");
            UUID anchor=(UUID)row(db,id).get("anchor_observation_id");
            db.update("UPDATE specialist_candidates SET state='ready',job_id=NULL,next_attempt_at=clock_timestamp() WHERE candidate_id=?",id);
            db.update("UPDATE observations SET deleted=true WHERE observation_id=?",anchor);
            new SpecialistCandidateProducer(queue,inputs,configured,policy).process(claim(db,queue,id,"withdrawn-source"));
            check("invalid".equals(row(db,id).get("state")),"withdrawn source replayed a result");
            db.update("UPDATE observations SET deleted=false WHERE observation_id=?",anchor);
            db.update("UPDATE specialist_candidates SET state='submitted',reason='specialist_job_registered',job_id=? WHERE candidate_id=?",job,id);
            checks.add("withdrawn_candidate_source_cannot_replay_existing_job");
            var page=queue.list(null,null,1,null);check(Boolean.TRUE.equals(page.get("has_more"))&&((List<?>)queue.list(null,null,1,UUID.fromString((String)page.get("next_cursor"))).get("items")).size()==1,"candidate cursor pagination failed");
            checks.add("candidate_management_has_scoped_bounded_cursor_pages");
            ui.put("all",queue.list(null,null,20,null));
            System.out.println("SPECIALIST_CANDIDATE_UI_FIXTURES="+JSON.writeValueAsString(Map.of("states",ui,"cameras",search.readableCameras().stream().sorted().map(camera->Map.of("camera_id",camera,"display_name",camera)).toList(),"scope","private_scheduling_state_fixtures_over_real_frames_not_model_accuracy")));
        }finally{
            for(var sample:cases){db.update("UPDATE capture_outbox SET plan=?::jsonb,registered_at=? WHERE frame_id=?",sample.source().get("plan_text"),sample.source().get("registered_at"),sample.source().get("frame_id"));db.update("UPDATE frames SET analysis_state='completed' WHERE frame_id=?",sample.source().get("frame_id"));}
            db.update("UPDATE processing_controls SET enabled=false WHERE control_key='analysis_claims'");
        }
    }
}
