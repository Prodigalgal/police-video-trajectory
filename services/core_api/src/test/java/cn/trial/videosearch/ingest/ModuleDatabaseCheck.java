package cn.trial.videosearch.ingest;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Actual PG/software faults over existing point results. No model or ES calls. */
@SuppressWarnings("unchecked")
public final class ModuleDatabaseCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static void phase(String name){System.out.println("MODULE_STAGE="+name);}
    private static long count(JdbcTemplate db,String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}

    private static TargetPipeline.Completion completion(JdbcTemplate db,UUID frame,AnalysisQueue.Job job,String worker){
        var records=db.queryForList("SELECT module_key,result::text FROM module_fixture_results WHERE frame_id=? ORDER BY module_key",frame);
        var targets=new ArrayList<TargetPipeline.TargetResult>();var additional=new ArrayList<ObservationStore.VisibleObject>();
        String model=null,prompt=null,scene=null;
        for(var record:records){
            var body=JSON.readValue((String)record.get("result"),Map.class);
            model=(String)body.get("model_id");prompt=(String)body.get("prompt_version");
            if("scene_context".equals(record.get("module_key"))){
                scene=(String)body.get("scene_description");
                additional.addAll(Arrays.asList(JSON.readValue(JSON.writeValueAsString(body.get("additional_objects")),ObservationStore.VisibleObject[].class)));
            }else targets.addAll(Arrays.asList(JSON.readValue(JSON.writeValueAsString(body.get("targets")),TargetPipeline.TargetResult[].class)));
        }
        return new TargetPipeline.Completion(job.jobId(),worker,job.generation(),model,prompt,targets,additional,scene);
    }

    private static TargetPipeline.Completion prepare(JdbcTemplate db,TransactionTemplate tx,AnalysisQueue frames,UUID frame,String worker){
        tx.executeWithoutResult(status->{
            db.update("UPDATE analysis_modules SET result=NULL,completed_at=NULL WHERE frame_id=?",frame);
            db.update("UPDATE frames SET analysis_state='queued' WHERE frame_id=?",frame);
            db.update("UPDATE analysis_jobs SET state='queued',attempts=0,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()-interval '1 second' WHERE frame_id=?",frame);
        });
        var job=tx.execute(status->frames.claimTargets(worker,600)).orElseThrow();
        check(frame.equals(job.frameId()),"parent claim frame differs");
        return completion(db,frame,job,worker);
    }

    private static ModuleQueue.Completion command(ModuleQueue.Job job,String worker){
        return new ModuleQueue.Completion(job.jobId(),worker,job.generation(),job.sourceId(),job.sourceSha256());
    }
    private static String owner(JdbcTemplate db,UUID job){return db.queryForObject("SELECT lease_owner FROM analysis_module_jobs WHERE job_id=?",String.class,job);}

    private static void drain(JdbcTemplate db,TransactionTemplate tx,ModuleQueue queue,UUID excluded){
        for(int number=0;number<100;number++){
            String worker="module-drain-"+number;
            var job=tx.execute(status->queue.claim(worker,120,null));
            if(job.isEmpty())return;
            check(!job.get().jobId().equals(excluded),"cooling module was claimed too early");
            var receipt=tx.execute(status->queue.complete(command(job.get(),worker)));
            check(Boolean.TRUE.equals(receipt.get("completed")),"module materialization not confirmed");
        }
        throw new IllegalStateException("module drain exceeded bounded dataset");
    }

    private static void assertRejected(Runnable operation,String expected){
        try{operation.run();throw new IllegalStateException("expected module recovery rejection");}
        catch(ModuleQueue.RecoveryRejected error){check(expected.equals(error.code),"recovery rejection differs");}
    }

    public static void main(String[] args) throws Exception {
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");
        check(schema!=null && schema.matches("queue_check_[0-9]{8}t[0-9]{6}z(?:_[0-9a-f]{8})?"),"isolated schema required");
        var source=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));
        var db=new JdbcTemplate(source);db.setQueryTimeout(15);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(25);
        check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema binding differs");
        List<Map<String,Object>> cases=JSON.readValue(Files.readString(Path.of(args[0]).resolve("cases.json")),List.class);
        check(cases.size()==5,"five retained semantic records required");
        List<UUID> ids=cases.stream().map(raw->UUID.fromString((String)raw.get("frame_id"))).toList();
        var checks=new ArrayList<String>();phase("migration_compatibility");
        long legacy=count(db,"analysis_modules");
        check(legacy==db.queryForObject("SELECT count(*) FROM analysis_modules m JOIN module_fixture_results f USING(frame_id,module_key) WHERE m.result=f.result AND m.completed_at=f.completed_at",Long.class),"migration changed old module results");
        check(count(db,"analysis_result_sources")==0 && count(db,"analysis_module_jobs")==0,"migration fabricated new work");
        checks.add("migration_preserves_legacy_results");
        var frames=new AnalysisQueue(db,20,new AnalysisQueuePolicy(2,5,5,300));
        var policy=new ModuleQueuePolicy(30,2,1,1,1,2,1,4);var modules=new ModuleQueue(db,policy);
        var targets=new TargetPipeline(db,frames,new ObservationStore(db),30,"/tmp",modules);
        var retained=new ArrayList<TargetPipeline.Completion>();int observationCount=0;
        phase("stage_saved_results");
        for(int ordinal=0;ordinal<3;ordinal++){
            var completion=prepare(db,tx,frames,ids.get(ordinal),"module-parent-"+ordinal);retained.add(completion);
            observationCount+=tx.execute(status->targets.complete(completion));
        }
        check(count(db,"analysis_result_sources")==3,"shared source duplicated or missing");
        long jobs=count(db,"analysis_module_jobs");
        check(count(db,"observations")==observationCount && count(db,"index_outbox")==observationCount,"validated observations/outbox differ");
        tx.execute(status->targets.complete(retained.getFirst()));
        check(count(db,"analysis_result_sources")==3 && count(db,"analysis_module_jobs")==jobs && count(db,"observations")==observationCount && count(db,"index_outbox")==observationCount,"parent replay duplicated data");
        checks.add("shared_result_saved_once");checks.add("parent_completion_replay_is_idempotent");checks.add("observations_and_index_handoff_preserved");

        phase("independent_quota");var ready=new CountDownLatch(8);var start=new CountDownLatch(1);var claimed=new ArrayList<ModuleQueue.Job>();
        try(var pool=Executors.newFixedThreadPool(8)){
            var requests=new ArrayList<Future<Optional<ModuleQueue.Job>>>();
            for(int ordinal=0;ordinal<8;ordinal++){
                int number=ordinal;
                requests.add(pool.submit(()->{
                    var independent=new ModuleQueue(db,policy);ready.countDown();check(start.await(5,TimeUnit.SECONDS),"module start deadline");
                    return tx.execute(status->independent.claim("module-concurrent-"+number,120,null));
                }));
            }
            check(ready.await(5,TimeUnit.SECONDS),"consumers not ready");start.countDown();
            for(var request:requests)request.get(30,TimeUnit.SECONDS).ifPresent(claimed::add);
        }
        check(claimed.size()==2 && claimed.stream().map(ModuleQueue.Job::moduleKey).distinct().count()==2,"global or kind quota exceeded");
        checks.add("global_module_quota");checks.add("per_kind_module_quota");

        phase("partial_success_and_failure");var successful=claimed.getFirst();var failing=claimed.getLast();
        String successOwner=owner(db,successful.jobId()),failedOwner=owner(db,failing.jobId());
        var command=command(successful,successOwner);var receipt=tx.execute(status->modules.complete(command));
        check(receipt.equals(tx.execute(status->modules.complete(command))),"lost completion acknowledgement is not idempotent");
        String successfulResult=db.queryForObject("SELECT result::text FROM analysis_modules WHERE frame_id=? AND module_key=?",String.class,successful.frameId(),successful.moduleKey());
        check(tx.execute(status->modules.fail(failing.jobId(),failedOwner,failing.generation(),"module_unavailable")),"module failure not recorded");
        check(!tx.execute(status->modules.fail(failing.jobId(),failedOwner,failing.generation(),"module_unavailable")),"duplicate failure consumed another attempt");
        check(db.queryForObject("SELECT next_attempt_at>clock_timestamp() FROM analysis_module_jobs WHERE job_id=?",Boolean.class,failing.jobId()),"failure did not back off");
        db.update("UPDATE analysis_module_jobs SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE job_id=?",failing.jobId());
        drain(db,tx,modules,failing.jobId());
        check(successfulResult.equals(db.queryForObject("SELECT result::text FROM analysis_modules WHERE frame_id=? AND module_key=?",String.class,successful.frameId(),successful.moduleKey())),"other module failure changed successful result");
        check(count(db,"observations")==observationCount && count(db,"index_outbox")==observationCount,"module failure changed parent data");
        checks.add("successful_module_preserved");checks.add("module_completion_ack_replay");checks.add("module_failure_backoff");checks.add("duplicate_failure_fenced");

        phase("replacement_and_recovery");db.update("UPDATE analysis_module_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",failing.jobId());
        var replacement=tx.execute(status->modules.claim("module-replacement",120,failing.moduleKey())).orElseThrow();
        check(replacement.jobId().equals(failing.jobId()) && replacement.generation()>failing.generation(),"replacement module differs");
        check(!modules.renew(failing.jobId(),failedOwner,failing.generation(),120),"old module heartbeat accepted");
        check(!tx.execute(status->modules.fail(failing.jobId(),failedOwner,failing.generation(),"consumer_interrupted")),"old failure accepted");
        try{tx.execute(status->modules.complete(command(failing,failedOwner)));throw new IllegalStateException("old completion accepted");}
        catch(IllegalStateException expected){check("module lease expired or replaced".equals(expected.getMessage()),"stale completion guard differs");}
        check(tx.execute(status->modules.fail(replacement.jobId(),"module-replacement",replacement.generation(),"module_unavailable")),"second module failure missing");
        check("failed".equals(db.queryForObject("SELECT state FROM analysis_module_jobs WHERE job_id=?",String.class,failing.jobId())),"module retry limit not enforced");
        tx.executeWithoutResult(status->targets.controlClaims(false,"module-proof"));
        check(tx.execute(status->modules.claim("module-paused",120,null)).isEmpty(),"paused module claimed");
        assertRejected(()->tx.execute(status->modules.retry(failing.jobId(),UUID.randomUUID(),replacement.generation(),"admin","核验单独恢复")),"processing_paused");
        tx.executeWithoutResult(status->targets.controlClaims(true,"module-proof"));
        UUID recovery=UUID.randomUUID();var restored=tx.execute(status->modules.retry(failing.jobId(),recovery,replacement.generation(),"admin","核验单独恢复"));
        var replayed=tx.execute(status->modules.retry(failing.jobId(),recovery,replacement.generation(),"admin","核验单独恢复"));
        check(JSON.readValue(JSON.writeValueAsString(restored),Map.class).equals(replayed),"recovery acknowledgement replay differs");
        assertRejected(()->tx.execute(status->modules.retry(failing.jobId(),recovery,replacement.generation(),"admin","改变原请求内容")),"module_recovery_conflict");
        var recovered=tx.execute(status->modules.claim("module-recovered",120,null)).orElseThrow();
        check(recovered.jobId().equals(failing.jobId()),"recovery affected another module");
        tx.execute(status->modules.complete(command(recovered,"module-recovered")));
        check(count(db,"analysis_module_recoveries")==1,"recovery audit duplicated");
        checks.add("stale_module_generation_rejected");checks.add("retry_exhaustion_retained");checks.add("module_pause_respected");
        checks.add("recover_only_failed_module");checks.add("recovery_idempotency_and_conflict");

        phase("saved_source_integrity");var fourth=prepare(db,tx,frames,ids.get(3),"module-parent-3");retained.add(fourth);
        observationCount+=tx.execute(status->targets.complete(fourth));
        var integrity=tx.execute(status->modules.claim("module-integrity",120,null)).orElseThrow();
        String originalPayload=db.queryForObject("SELECT source_payload::text FROM analysis_result_sources WHERE source_id=?",String.class,integrity.sourceId());
        db.update("UPDATE analysis_result_sources SET source_payload=jsonb_set(source_payload,'{scene_description}','\"tampered software fixture\"') WHERE source_id=?",integrity.sourceId());
        try{tx.execute(status->modules.complete(command(integrity,"module-integrity")));throw new IllegalStateException("changed source accepted");}
        catch(ModuleResultSource.Invalid expected){checks.add("tampered_source_rejected");}
        check(db.queryForObject("SELECT result IS NULL FROM analysis_modules WHERE frame_id=? AND module_key=?",Boolean.class,integrity.frameId(),integrity.moduleKey()),"bad source materialized a result");
        db.update("UPDATE analysis_result_sources SET source_payload=CAST(? AS jsonb) WHERE source_id=?",originalPayload,integrity.sourceId());
        tx.execute(status->modules.complete(command(integrity,"module-integrity")));

        phase("module_capacity");var fifth=prepare(db,tx,frames,ids.get(4),"module-parent-4");
        var small=new ModuleQueue(db,new ModuleQueuePolicy(3,2,1,1,1,2,1,4));
        var constrained=new TargetPipeline(db,frames,new ObservationStore(db),30,"/tmp",small);
        check(!tx.execute(status->constrained.claim("blocked-parent",120)).isPresent(),"full module queue did not backpressure parent claims");
        long observationsBefore=count(db,"observations"),sourcesBefore=count(db,"analysis_result_sources");
        try{tx.execute(status->constrained.complete(fifth));throw new IllegalStateException("module capacity exceeded");}
        catch(ModuleQueue.CapacityExceeded expected){checks.add("hard_module_capacity_rollback");}
        check(count(db,"observations")==observationsBefore && count(db,"analysis_result_sources")==sourcesBefore,"capacity rollback left partial parent data");
        drain(db,tx,modules,null);observationCount+=tx.execute(status->constrained.complete(fifth));
        check(db.queryForObject("SELECT count(*) FROM analysis_module_jobs WHERE state IN('queued','active')",Long.class)<=3,"pending capacity exceeded");
        checks.add("parent_claim_backpressure");
        var expiring=tx.execute(status->small.claim("module-expiring",120,null)).orElseThrow();
        db.update("UPDATE analysis_module_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?",expiring.jobId());
        var next=tx.execute(status->small.claim("module-next",120,expiring.moduleKey()));
        check(next.isEmpty(),"expired module skipped cooldown");
        check(db.queryForObject("SELECT state='queued' AND attempts=1 AND last_error_code='lease_expired' AND lease_owner IS NULL FROM analysis_module_jobs WHERE job_id=?",Boolean.class,expiring.jobId()),"expired module state differs");
        db.update("UPDATE analysis_module_jobs SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE job_id=?",expiring.jobId());
        drain(db,tx,modules,expiring.jobId());
        checks.add("expired_module_lease_cooldown");

        phase("scoped_management");String camera=(String)((Map<?,?>)cases.get(4)).get("camera_id");
        var search=new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","disabled",
                "https://disabled.invalid","/database-ca/ca.crt","disabled","disabled","disabled",camera,"police-video-observations-module-check");
        var admin=new AdminService(db,search,frames,null,modules);var page=admin.moduleJobs("all",camera,null,1,null);
        check(((List<?>)page.get("items")).size()==1 && Boolean.TRUE.equals(page.get("has_more")),"module pagination differs");
        var first=(Map<?,?>)((List<?>)page.get("items")).getFirst();check(camera.equals(first.get("camera_id")),"module camera scope leaked");
        var second=admin.moduleJobs("all",camera,null,1,UUID.fromString((String)page.get("next_cursor")));
        check(!first.get("job_id").equals(((Map<?,?>)((List<?>)second.get("items")).getFirst()).get("job_id")),"module pages overlap");
        try{admin.moduleJobs("all","recording-03",null,20,null);throw new IllegalStateException("outside camera allowed");}
        catch(AdminService.Rejected expected){check(expected.status==404,"scoped module read differs");}
        check(admin.overview().containsKey("module_load"),"module overview missing");
        checks.add("module_admin_scoped_pagination");
        db.update("UPDATE analysis_module_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",expiring.jobId());
        drain(db,tx,modules,null);

        phase("legacy_replay");UUID legacyFrame=ids.getFirst();
        tx.executeWithoutResult(status->{
            db.update("DELETE FROM analysis_module_recoveries WHERE job_id IN(SELECT job_id FROM analysis_module_jobs WHERE frame_id=?)",legacyFrame);
            db.update("DELETE FROM analysis_module_jobs WHERE frame_id=?",legacyFrame);
            db.update("DELETE FROM analysis_result_sources WHERE frame_id=?",legacyFrame);
        });
        long beforeLegacy=count(db,"analysis_module_jobs");tx.execute(status->targets.complete(retained.getFirst()));
        check(count(db,"analysis_module_jobs")==beforeLegacy && db.queryForObject("SELECT count(*) FROM analysis_result_sources WHERE frame_id=?",Long.class,legacyFrame)==0,"legacy replay fabricated work");
        check(count(db,"observations")==observationCount && count(db,"index_outbox")==observationCount,"module-only operations changed observations/index events");
        var changed=retained.get(1);var conflict=new TargetPipeline.Completion(changed.jobId(),changed.workerId(),changed.generation(),changed.modelId(),changed.promptVersion(),changed.targets(),changed.additionalObjects(),"changed software replay");
        try{tx.execute(status->targets.complete(conflict));throw new IllegalStateException("conflicting parent source accepted");}
        catch(ModuleResultSource.Invalid expected){checks.add("conflicting_shared_result_replay_rejected");}
        checks.add("legacy_completed_modules_not_requeued");checks.add("module_recovery_never_reindexes_parent");
        tx.executeWithoutResult(status->targets.controlClaims(false,"module-proof-finished"));
        System.out.println("MODULE_CHECK_RESULT="+JSON.writeValueAsString(Map.of("result","passed","real_points",4,"retained_records",5,
                "independent_consumers",8,"new_model_requests",0,"checks",checks,"observations",observationCount,
                "index_outbox_events",count(db,"index_outbox"),"module_jobs",count(db,"analysis_module_jobs"))));
    }
}
