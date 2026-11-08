package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real queue/HTTPS consumer/encoding in a private schema and disposable output volume. */
@SuppressWarnings("unchecked")
public final class MediaJobsDatabaseCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void rejected(Runnable action,String code){try{action.run();throw new IllegalStateException("expected "+code);}catch(InputRejected error){check(code.equals(error.code),"rejection differs: "+error.code);}}
    private static SearchOrchestrator scope(JdbcTemplate db,String cameras){return new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","disabled","https://disabled.invalid","/database-ca/ca.crt","disabled","disabled","disabled",cameras,"police-video-observations-media-jobs-check");}
    public static void main(String[] args)throws Exception{
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");check(schema!=null&&schema.matches("queue_check_[0-9]{8}t[0-9]{6}z_[0-9a-f]{8}"),"private schema required");
        var source=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));var db=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);
        check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema mismatch");
        Path root=Path.of("/tmp/media-jobs-check");Files.createDirectories(root.resolve("clips"));
        var search=scope(db,"recording-01,recording-02,recording-03,recording-04");var sessions=new SessionMediaInputs(db,new FrameRecordings(db,search,new StreamRecordingFiles("/recordings-live"),new EvidenceFiles("/evidence"),manager,false));var files=new MediaClipFiles(root.resolve("clips").toString(),"/recordings");var clips=new MediaClips(db,search,files,manager,sessions);
        var jobs=new MediaJobs(db,search,files,clips,sessions,manager,true,20,1,3);var checks=new ArrayList<String>();
        List<Map<String,Object>> cases=JSON.readValue(Files.readString(Path.of("/verification/cases.json")),List.class);
        var identities=new ArrayList<UUID>();var requests=new ArrayList<MediaJobs.Create>();
        for(var sample:cases.subList(0,4)){
            var request=new MediaJobs.Create(UUID.randomUUID(),UUID.fromString((String)sample.get("source_id")),10000,14000);requests.add(request);
            var job=jobs.create(request);identities.add(UUID.fromString((String)job.get("job_id")));
            var input=db.queryForList("SELECT source_sha256,source_bytes FROM media_production_jobs WHERE job_id=?",identities.getLast()).getFirst();
            check(input.get("source_sha256").equals(sample.get("expected_source_sha256"))&&((Number)input.get("source_bytes")).longValue()==((Number)sample.get("expected_source_bytes")).longValue(),"original upload inventory changed");
            check("queued".equals(job.get("state")),"job was not queued");check(job.get("job_id").equals(jobs.create(request).get("job_id")),"request replay differs");
            check(job.get("job_id").equals(jobs.create(new MediaJobs.Create(UUID.randomUUID(),request.sourceId(),10000,14000)).get("job_id")),"identical input was not deduplicated");
        }
        check(identities.size()==4&&db.queryForObject("SELECT count(*) FROM media_production_jobs",Integer.class)==4,"four real jobs required");checks.add("four_real_archive_jobs_request_replay_and_input_deduplication");
        var first=requests.getFirst();rejected(()->jobs.create(new MediaJobs.Create(first.requestId(),first.sourceId(),15000,19000)),"media_request_conflict");checks.add("request_identity_cannot_change_its_window");
        db.update("UPDATE processing_controls SET enabled=false WHERE control_key='analysis_claims'");check(jobs.claim("paused-worker",120).isEmpty(),"paused queue claimed work");check("paused".equals(jobs.read(identities.getFirst()).get("dispatch_state")),"paused dispatch status differs");checks.add("global_pause_prevents_media_claims");
        db.update("UPDATE processing_controls SET enabled=true WHERE control_key='analysis_claims'");
        var owned=new ArrayList<MediaJobs.Job>();
        try(var pool=Executors.newFixedThreadPool(8)){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Optional<MediaJobs.Job>>>();
            for(int n=0;n<8;n++){int number=n;futures.add(pool.submit(()->{start.await();return jobs.claim("contender-"+number,120);}));}start.countDown();
            for(var future:futures)future.get(45,TimeUnit.SECONDS).ifPresent(owned::add);
        }
        check(owned.size()==1,"global encoding quota exceeded");var active=owned.getFirst();String owner=db.queryForObject("SELECT lease_owner FROM media_production_jobs WHERE job_id=?",String.class,active.jobId());checks.add("eight_independent_consumers_share_one_database_encoding_slot");
        db.update("UPDATE processing_controls SET enabled=false WHERE control_key='analysis_claims'");check("running".equals(jobs.read(active.jobId()).get("dispatch_state")),"pause relabeled active work");db.update("UPDATE processing_controls SET enabled=true WHERE control_key='analysis_claims'");checks.add("pause_does_not_relabel_active_work");
        check(!jobs.renew(active.jobId(),owner,active.generation()+1,120)&&jobs.renew(active.jobId(),owner,active.generation(),120),"renewal fence differs");
        check(!jobs.fail(active.jobId(),owner,active.generation()+1,"media_timeout"),"wrong generation failed work");checks.add("renewal_and_failure_require_current_generation");
        db.update("UPDATE media_production_jobs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?",active.jobId());
        check("lease_expired".equals(jobs.read(active.jobId()).get("dispatch_state")),"expired lease phase missing");
        rejected(()->jobs.complete(new MediaJobs.Complete(active.jobId(),owner,active.generation(),active.inputSha256(),"a".repeat(64))),"media_lease_expired");
        jobs.claim("expiry-recovery",120);check(((Number)jobs.read(active.jobId()).get("attempts")).intValue()==1&&"retry_wait".equals(jobs.read(active.jobId()).get("dispatch_state")),"expired attempt/backoff not accounted");checks.add("expired_lease_recovered_with_backoff_and_old_completion_rejected");
        // Fault state is private metadata, restored before the real consumer starts.
        db.update("UPDATE media_production_jobs SET state='queued',attempts=0,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp(),last_error_code=NULL");
        var failed=jobs.claim("failure-worker",120).orElseThrow();check(jobs.fail(failed.jobId(),"failure-worker",failed.generation(),"media_source_changed"),"permanent failure not recorded");
        var failure=jobs.read(failed.jobId());check("failed".equals(failure.get("state")),"changed source was retried automatically");
        var retry=new MediaJobs.Retry(UUID.randomUUID(),((Number)failure.get("revision")).longValue());jobs.retry(failed.jobId(),retry);check("queued".equals(jobs.retry(failed.jobId(),retry).get("state")),"retry replay differs");
        rejected(()->jobs.retry(failed.jobId(),new MediaJobs.Retry(UUID.randomUUID(),retry.revision())),"media_revision_conflict");checks.add("permanent_failure_requires_revision_fenced_idempotent_retry");
        var exhausted=jobs.claim("exhausted-worker",120).orElseThrow();db.update("UPDATE media_production_jobs SET attempts=2 WHERE job_id=?",exhausted.jobId());jobs.fail(exhausted.jobId(),"exhausted-worker",exhausted.generation(),"media_timeout");
        var exhaustedState=jobs.read(exhausted.jobId());check("failed".equals(exhaustedState.get("state"))&&((Number)exhaustedState.get("attempts")).intValue()==3,"attempt limit was not enforced");jobs.retry(exhausted.jobId(),new MediaJobs.Retry(UUID.randomUUID(),((Number)exhaustedState.get("revision")).longValue()));checks.add("retry_exhaustion_stops_automatic_work");
        var invalid=jobs.claim("invalid-input-worker",120).orElseThrow();jobs.fail(invalid.jobId(),"invalid-input-worker",invalid.generation(),"media_input_invalid");var invalidState=jobs.read(invalid.jobId());check("failed".equals(invalidState.get("state")),"invalid input kept retrying");jobs.retry(invalid.jobId(),new MediaJobs.Retry(UUID.randomUUID(),((Number)invalidState.get("revision")).longValue()));checks.add("invalid_input_fails_without_useless_automatic_retries");
        var limited=new MediaJobs(db,search,files,clips,sessions,manager,true,4,1,3);
        rejected(()->limited.create(new MediaJobs.Create(UUID.randomUUID(),first.sourceId(),15000,19000)),"media_jobs_full");checks.add("queue_capacity_rejects_new_work_without_dropping_old_jobs");
        var restrictedSearch=scope(db,"recording-04");var restrictedClips=new MediaClips(db,restrictedSearch,files,manager,sessions);var restricted=new MediaJobs(db,restrictedSearch,files,restrictedClips,sessions,manager,true,20,1,3);
        rejected(()->restricted.read(identities.getFirst()),"media_job_unavailable");rejected(()->restricted.create(new MediaJobs.Create(UUID.randomUUID(),first.sourceId(),15000,19000)),"media_source_unavailable");checks.add("camera_scope_precedes_original_file_access");
        var scopeJob=restricted.claim("scope-worker",120).orElseThrow();check("recording-04".equals(scopeJob.cameraId()),"consumer escaped configured camera scope");jobs.fail(scopeJob.jobId(),"scope-worker",scopeJob.generation(),"media_timeout");checks.add("consumer_only_claims_configured_camera_scope");
        var extra=new ArrayList<UUID>();for(long start:List.of(15000L,20000L))extra.add(UUID.fromString((String)jobs.create(new MediaJobs.Create(UUID.randomUUID(),first.sourceId(),start,start+4000)).get("job_id")));
        UUID cursor=null;var seen=new HashSet<UUID>();boolean more;
        do{var page=jobs.list("recording-01",1,cursor);var entries=(List<Map<String,Object>>)page.get("items");check(entries.size()==1,"bounded page differs");check(seen.add(UUID.fromString((String)entries.getFirst().get("job_id"))),"cursor duplicated job");more=Boolean.TRUE.equals(page.get("has_more"));cursor=more?UUID.fromString((String)page.get("next_cursor")):null;}while(more);
        check(seen.size()==3,"pagination lost jobs");for(UUID id:extra){db.update("DELETE FROM media_production_requests WHERE job_id=?",id);db.update("DELETE FROM media_production_jobs WHERE job_id=?",id);}checks.add("bounded_camera_pagination_preserves_all_cursor_pages");
        db.update("UPDATE media_production_jobs SET next_attempt_at=clock_timestamp()+interval '1 hour'");db.update("UPDATE media_production_jobs SET next_attempt_at=clock_timestamp()-interval '1 hour' WHERE job_id=?",identities.getFirst());db.update("UPDATE sources SET source_time_origin='verified' WHERE source_id=?",first.sourceId());
        check(jobs.claim("changed-source-worker",120).isEmpty()&&"media_source_changed".equals(jobs.read(identities.getFirst()).get("last_error_code")),"changed source was dispatched");db.update("UPDATE sources SET source_time_origin='unknown' WHERE source_id=?",first.sourceId());jobs.retry(identities.getFirst(),new MediaJobs.Retry(UUID.randomUUID(),((Number)jobs.read(identities.getFirst()).get("revision")).longValue()));checks.add("changed_source_fingerprint_stops_before_encoding");
        db.update("UPDATE media_production_jobs SET attempts=0,next_attempt_at=clock_timestamp()");
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(MediaJobController.class,()->new MediaJobController(jobs,search,"verification-only-media-worker"));context.register(DelegatingWebFluxConfiguration.class);context.refresh();
            var handler=new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build());
            var ssl=SslContextBuilder.forServer(Path.of("/verification/tls.crt").toFile(),Path.of("/verification/tls.key").toFile()).build();
            var server=HttpServer.create().host("127.0.0.1").port(18080).secure(spec->spec.sslContext(ssl)).handle(handler).bindNow();
            try{
                Files.writeString(root.resolve("ready.json"),"{\"ready\":true}");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(420);
                while(db.queryForObject("SELECT count(*) FROM media_production_jobs WHERE state='completed'",Integer.class)<4){
                    if(db.queryForObject("SELECT count(*) FROM media_production_jobs WHERE state='failed'",Integer.class)>0)throw new IllegalStateException("real media job failed: "+db.queryForList("SELECT state,last_error_code,attempts FROM media_production_jobs"));
                    if(System.nanoTime()>deadline)throw new IllegalStateException("real media consumer did not finish: "+db.queryForList("SELECT state,last_error_code,attempts FROM media_production_jobs"));Thread.sleep(500);
                }
                checks.add("real_https_consumer_and_four_bounded_encoding_subprocesses_complete");
                for(UUID id:identities){
                    var asset=clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1);check(asset.manifest().get("samples") instanceof List<?> samples&&samples.size()==4,"four actual samples required");
                    var current=db.queryForList("SELECT * FROM media_production_jobs WHERE job_id=?",id).getFirst();
                    var complete=new MediaJobs.Complete(id,(String)current.get("lease_owner"),((Number)current.get("lease_generation")).longValue(),(String)current.get("input_sha256"),(String)current.get("manifest_sha256"));
                    check(Boolean.TRUE.equals(jobs.complete(complete).get("completed")),"completion ack replay differs");
                    check(!jobs.fail(id,complete.workerId(),complete.generation(),"media_timeout"),"late failure overwrote success");
                }
                checks.add("four_registered_mp4s_and_sixteen_real_jpegs_verify_against_originals");checks.add("lost_completion_ack_and_late_failure_preserve_success");
                check(db.queryForObject("SELECT count(*) FROM recorded_media_clips",Integer.class)==4,"duplicate registered media");
                check(db.queryForObject("SELECT count(*) FROM audit_events WHERE action='complete_media_job'",Integer.class)==4,"completion audit duplicated");checks.add("one_registry_entry_and_completion_audit_per_job");
                check(db.queryForObject("SELECT count(*) FROM observations",Long.class)==0&&db.queryForObject("SELECT count(*) FROM analysis_jobs",Long.class)==0&&db.queryForObject("SELECT count(*) FROM index_outbox",Long.class)==0,"media work generated semantics or AI jobs");checks.add("no_observations_inference_jobs_or_index_events_created");
                Files.writeString(root.resolve("done.json"),"{\"result\":\"passed\"}");Thread.sleep(1500);
            }finally{server.disposeNow();}
        }
        System.out.println("MEDIA_JOBS_CHECK_RESULT="+JSON.writeValueAsString(Map.of("result","passed","checks",checks,"real_jobs",4,"registered_clips",4,"real_samples",16,"new_model_requests",0,"https_worker",true)));
    }
}
