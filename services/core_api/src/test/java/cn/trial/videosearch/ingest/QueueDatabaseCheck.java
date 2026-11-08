package cn.trial.videosearch.ingest;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real PostgreSQL queue/fencing checks over copied 01-04 captures, no model calls. */
public final class QueueDatabaseCheck {
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}

    public static void main(String[] args) throws Exception {
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");
        check(schema!=null && schema.matches("queue_check_[0-9]{8}t[0-9]{6}z(?:_[0-9a-f]{8})?"),"isolated schema required");
        var dataSource=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,
                System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));
        var db=new JdbcTemplate(dataSource);db.setQueryTimeout(15);
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setTimeout(20);
        check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema binding");
        check(db.queryForObject("SELECT count(*) FROM capture_outbox",Long.class)==4,"four actual captures");
        var policy=new AnalysisQueuePolicy(2,2,5,300);var queue=new AnalysisQueue(db,4,policy);
        var start=new CountDownLatch(1);var ready=new CountDownLatch(8);var claimed=new ArrayList<AnalysisQueue.Job>();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Optional<AnalysisQueue.Job>>>();
            for(int number=0;number<8;number++) {
                int ordinal=number;
                futures.add(pool.submit(()->{
                    var independentConsumer=new AnalysisQueue(db,4,policy);
                    ready.countDown();check(start.await(5,TimeUnit.SECONDS),"consumer start timeout");
                    return transactions.execute(status->independentConsumer.claimTargets("queue-check-"+ordinal,120));
                }));
            }
            check(ready.await(5,TimeUnit.SECONDS),"consumer pool not ready");start.countDown();
            for(var future:futures)future.get(30,TimeUnit.SECONDS).ifPresent(claimed::add);
        }
        check(claimed.size()==2 && claimed.stream().map(AnalysisQueue.Job::jobId).distinct().count()==2,"global quota exceeded");
        var load=queue.load();check(((Number)load.get("active")).intValue()==2 && Boolean.TRUE.equals(load.get("consumer_saturated")) && Boolean.TRUE.equals(load.get("producer_blocked")),"quota/producer health");
        var selected=claimed.getFirst();String owner=db.queryForObject("SELECT lease_owner FROM analysis_jobs WHERE job_id=?",String.class,selected.jobId());
        check(transactions.execute(status->queue.fail(selected.jobId(),owner,selected.generation(),"model_timeout")),"failure acknowledgement");
        check(!transactions.execute(status->queue.fail(selected.jobId(),owner,selected.generation(),"model_timeout")),"duplicate failure changed attempts");
        check(db.queryForObject("SELECT attempts FROM analysis_jobs WHERE job_id=?",Integer.class,selected.jobId())==1,"duplicate failure charged twice");
        // Only this isolated fixture is rearranged to exercise each recovery edge.
        transactions.executeWithoutResult(status->{
            db.update("UPDATE analysis_jobs SET state='failed',lease_owner=NULL,lease_expires_at=NULL WHERE job_id<>?",selected.jobId());
            db.update("UPDATE frames SET analysis_state='failed' WHERE frame_id<>?",selected.frameId());
        });
        check(transactions.execute(status->queue.claimTargets("retry-check",120)).isEmpty(),"retry ignored backoff");
        db.update("UPDATE analysis_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",selected.jobId());
        var replacement=transactions.execute(status->queue.claimTargets("replacement",120)).orElseThrow();
        check(replacement.generation()>selected.generation(),"replacement generation did not advance");
        check(!queue.renew(selected.jobId(),owner,selected.generation(),120),"stale heartbeat accepted");
        check(!transactions.execute(status->queue.fail(selected.jobId(),owner,selected.generation(),"consumer_interrupted")),"stale failure accepted");
        check(queue.renew(replacement.jobId(),"replacement",replacement.generation(),120),"current heartbeat rejected");
        db.update("UPDATE analysis_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?",selected.jobId());
        check(transactions.execute(status->queue.claimTargets("expired-check",120)).isEmpty(),"exhausted work claimed");
        var exhausted=db.queryForMap("SELECT j.state,j.attempts,j.last_error_code,j.lease_owner,j.lease_expires_at,f.analysis_state FROM analysis_jobs j JOIN frames f USING(frame_id) WHERE job_id=?",selected.jobId());
        check("failed".equals(exhausted.get("state")) && "failed".equals(exhausted.get("analysis_state")) && ((Number)exhausted.get("attempts")).intValue()==2 && "lease_expired".equals(exhausted.get("last_error_code")) && exhausted.get("lease_owner")==null && exhausted.get("lease_expires_at")==null,"exhausted lease state differs");
        var modules=new ModuleQueue(db,new ModuleQueuePolicy(1500,3,1,1,1,5,5,300));
        var targets=new TargetPipeline(db,queue,null,8,"/tmp",modules);
        transactions.executeWithoutResult(status->{
            db.update("UPDATE analysis_jobs SET state='queued',attempts=0,next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",selected.jobId());
            db.update("UPDATE frames SET analysis_state='queued' WHERE frame_id=?",selected.frameId());
            targets.controlClaims(false,"queue-check");
        });
        check(transactions.execute(status->queue.claimTargets("paused-check",120)).isEmpty(),"paused queue claimed");
        transactions.executeWithoutResult(status->targets.controlClaims(true,"queue-check"));
        check(transactions.execute(status->queue.claim("legacy-check",120)).isEmpty(),"legacy consumer claimed target work");
        var restarted=transactions.execute(status->queue.claimTargets("restarted",120)).orElseThrow();
        db.update("UPDATE analysis_jobs SET lease_expires_at=NULL WHERE job_id=?",selected.jobId());
        check(transactions.execute(status->queue.claimTargets("cooldown-check",120)).isEmpty(),"expired lease retried without cooldown");
        var cooling=db.queryForMap("SELECT state,attempts,last_error_code,lease_owner,next_attempt_at>clock_timestamp() AS delayed FROM analysis_jobs WHERE job_id=?",selected.jobId());
        check("queued".equals(cooling.get("state")) && ((Number)cooling.get("attempts")).intValue()==1 && Boolean.TRUE.equals(cooling.get("delayed")) && cooling.get("lease_owner")==null,"recoverable expiry state differs");
        check(!queue.renew(restarted.jobId(),"restarted",restarted.generation(),120),"expired heartbeat resurrected lease");
        var receipt=db.queryForMap("SELECT receipt,payload_sha256 FROM capture_outbox WHERE frame_id=?",selected.frameId());
        var activation=new TargetPipeline.Activation(selected.frameId(),(UUID)receipt.get("receipt"),(String)receipt.get("payload_sha256"));
        check(transactions.execute(status->targets.activate(activation)).orElseThrow().equals(selected.jobId()),"registered producer replay differs");
        check(db.queryForObject("SELECT count(*) FROM analysis_jobs",Long.class)==4,"producer replay duplicated jobs");
        transactions.executeWithoutResult(status->targets.controlClaims(false,"queue-check-finished"));
        String camera=db.queryForObject("SELECT camera_id FROM capture_outbox WHERE frame_id=?",String.class,selected.frameId());
        var search=new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","unusable-in-check",
                "https://disabled.invalid","/database-ca/ca.crt","disabled","unusable-in-check","unusable-in-check",
                camera,"police-video-observations-queue-check");
        var admin=new AdminService(db,search,queue,null,modules);var overview=admin.overview();
        check(((Number)((Map<?,?>)overview.get("counts")).get("cameras")).intValue()==1,"admin overview scope leaked");
        check(((Number)((Map<?,?>)overview.get("queue_load")).get("retry_waiting")).intValue()==1,"admin retry status differs");
        var page=admin.jobs("all",null,20,null);var items=(List<?>)page.get("items");
        check(items.size()==1 && "retry_wait".equals(((Map<?,?>)items.getFirst()).get("scheduling_state"))
                && Boolean.FALSE.equals(((Map<?,?>)items.getFirst()).get("can_retry")) && Boolean.TRUE.equals(page.get("processing_paused")),"scoped admin queue state differs");
        var result=Map.of("result","passed","real_points",4,"independent_consumer_instances",8,
                "active_limit",2,"new_model_requests",0,"checks",List.of("global_admission","producer_backpressure",
                    "idempotent_capture_enqueue","duplicate_failure_fencing","retry_cooldown","replacement_generation",
                    "stale_heartbeat_rejected","retry_exhaustion","pause_controls","legacy_consumer_isolation","expired_lease_cooldown",
                    "admin_scoped_queue_load","admin_retry_phase_and_pause","missing_lease_expiry_recovery"));
        System.out.println("QUEUE_CHECK_RESULT="+new ObjectMapper().writeValueAsString(result));
    }
}
