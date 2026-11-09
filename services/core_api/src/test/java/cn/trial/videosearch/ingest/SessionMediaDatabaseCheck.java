package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable PG/HTTPS test, using only actual 01-04 same-reader RTSPS packets. */
@SuppressWarnings("unchecked")
public final class SessionMediaDatabaseCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void rejected(Runnable action,String code){try{action.run();throw new IllegalStateException("expected "+code);}catch(InputRejected error){check(code.equals(error.code),"rejection differs: "+error.code);}}
    private static SearchOrchestrator scope(JdbcTemplate db,String cameras){return new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","disabled","https://disabled.invalid","/database-ca/ca.crt","disabled","disabled","disabled",cameras,"police-video-observations-session-media-check",new cn.trial.videosearch.evidence.ReviewedPlateFacts(db,false));}
    private static FrameRecordings.Register reference(Map<String,Object> row){var ref=(Map<String,Object>)row.get("recording");return new FrameRecordings.Register(UUID.fromString((String)row.get("frame_id")),(String)row.get("payload_sha256"),(String)ref.get("namespace"),UUID.fromString((String)ref.get("segment_id")),(String)row.get("manifest_sha256"),((Number)ref.get("source_pts_us")).longValue(),(String)ref.get("packet_sha256"));}
    public static void main(String[] args)throws Exception{
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");check(schema!=null&&schema.matches("queue_check_[0-9]{8}t[0-9]{6}z_[0-9a-f]{8}"),"private schema required");
        var source=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));var db=new JdbcTemplate(source);var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);
        check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema mismatch");
        db.update("UPDATE processing_controls SET enabled=false WHERE control_key='analysis_claims'");
        Path root=Path.of("/tmp/media-jobs-check"),nativeRoot=Path.of("/tmp/native-check");Files.createDirectories(root.resolve("clips"));
        var search=scope(db,"recording-01,recording-02,recording-03,recording-04");var images=new EvidenceFiles(nativeRoot.resolve("evidence").toString());var natives=new StreamRecordingFiles(nativeRoot.resolve("recordings").toString());
        var recordings=new FrameRecordings(db,search,natives,images,manager,true);var sessions=new SessionMediaInputs(db,recordings);var files=new MediaClipFiles(root.resolve("clips").toString(),"/recordings");var clips=new MediaClips(db,search,files,manager,sessions);
        var jobs=new MediaJobs(db,search,files,clips,sessions,manager,true,4,1,3);var checks=new ArrayList<String>();
        var targets=new TargetPipeline(db,null,null,2000,nativeRoot.resolve("evidence").toString(),null);
        List<Map<String,Object>> candidates=JSON.readValue(Files.readString(nativeRoot.resolve("candidates.json")),List.class);
        check(candidates.size()==8,"eight actual frames required");var sources=new HashMap<UUID,UUID>();var ids=new LinkedHashSet<UUID>();
        for(var row:candidates){
            check("rtsp".equals(row.get("source_type")),"native RTSP source required");UUID task=UUID.fromString((String)row.get("task_id")),frame=UUID.fromString((String)row.get("frame_id"));
            if(!sources.containsKey(task)){UUID id=UUID.randomUUID();sources.put(task,id);db.update("INSERT INTO sources(source_id,camera_id,source_type,source_ref,source_time_origin,enabled) VALUES(?,?,'rtsp',?,'unknown',true)",id,row.get("camera_id"),row.get("source_ref"));db.update("INSERT INTO processing_tasks(task_id,source_id,task_type,state,lease_owner,lease_generation,lease_expires_at) VALUES(?,?,'rtsp','active',?,1,clock_timestamp()+interval '20 minutes')",task,id,row.get("worker_id"));}
            var image=images.read(frame,"evidence:"+frame.toString().replace("-",""));check(image!=null,"real JPEG missing");
            var plan=Map.<String,Object>of("version","targets-v1","strategy","frame_batch","detection_state","unknown","image_width",image.width(),"image_height",image.height(),"targets",List.of());
            var capture=new TargetPipeline.Capture(frame,task,(String)row.get("worker_id"),1,"evidence:"+frame.toString().replace("-",""),(String)row.get("payload_sha256"),null,null,UUID.fromString((String)row.get("scene_run_id")),((Number)row.get("media_epoch")).longValue(),((Number)row.get("relative_media_ms")).longValue(),plan);
            check(tx.execute(status->targets.register(capture))!=null,"capture registration failed");recordings.register(reference(row));
            var request=new MediaJobs.SessionCreate(UUID.randomUUID(),(String)row.get("manifest_sha256"));rejected(()->jobs.createSession(frame,request),"media_session_unavailable");
            // A private permission-state fixture; no model result or semantic observation.
            db.update("INSERT INTO frames(frame_id,task_id,lease_generation,evidence_ref,source_time_origin,received_at,analysis_state) SELECT frame_id,task_id,lease_generation,evidence_ref,source_time_origin,registered_at,'completed' FROM capture_outbox WHERE frame_id=?",frame);
            var job=jobs.createSession(frame,request);check("paused".equals(job.get("dispatch_state")),"pause status lost");UUID id=UUID.fromString((String)job.get("job_id"));ids.add(id);
            check(job.get("job_id").equals(jobs.createSession(frame,request).get("job_id")),"request replay differs");
            check(job.get("job_id").equals(jobs.createSession(frame,new MediaJobs.SessionCreate(UUID.randomUUID(),request.recordingManifestSha256())).get("job_id")),"same segment encoded per frame");
            check(((List<?>)jobs.listForFrame(frame,1,null).get("items")).size()==1,"frame list missing link");
        }
        check(sources.size()==4&&ids.size()==4&&db.queryForObject("SELECT count(*) FROM media_job_frames",Integer.class)==8,"four jobs/eight exact links required");checks.add("four_real_rtsp_segments_eight_distinct_frames_deduplicate_to_four_jobs");checks.add("private_completed_permission_state_and_public_pending_denial");checks.add("frame_task_listing_and_request_replay");
        var first=candidates.getFirst();UUID frame=UUID.fromString((String)first.get("frame_id")),id=UUID.fromString((String)((List<Map<String,Object>>)jobs.listForFrame(frame,1,null).get("items")).getFirst().get("job_id"));var descriptor=sessions.metadata(frame,(String)first.get("manifest_sha256"),true).descriptor();
        check(jobs.claim("paused",120,List.of("session")).isEmpty(),"global pause ignored");db.update("UPDATE processing_controls SET enabled=true WHERE control_key='analysis_claims'");
        check(jobs.claim("old-worker",120).isEmpty(),"old worker received session input");checks.add("pause_and_legacy_worker_capability_fences");
        db.update("UPDATE media_production_jobs SET next_attempt_at=clock_timestamp()+interval '1 hour'");db.update("UPDATE media_production_jobs SET next_attempt_at=clock_timestamp(),input_descriptor=jsonb_set(input_descriptor,'{duration_us}','true'::jsonb) WHERE job_id=?",id);
        check(jobs.claim("bad-descriptor",120,List.of("session")).isEmpty()&&"failed".equals(jobs.read(id).get("state")),"bad descriptor jammed queue");db.update("UPDATE media_production_jobs SET input_descriptor=?::jsonb WHERE job_id=?",JSON.writeValueAsString(descriptor),id);
        var failed=jobs.read(id);var retry=new MediaJobs.Retry(UUID.randomUUID(),((Number)failed.get("revision")).longValue());jobs.retry(id,retry);jobs.retry(id,retry);checks.add("malformed_descriptor_isolated_and_revision_fenced_retry_replays");
        db.update("UPDATE media_job_frames SET packet_sha256=? WHERE job_id=?", "0".repeat(64),id);rejected(()->sessions.verifyJob(id,descriptor,false),"media_session_changed");db.update("UPDATE media_job_frames m SET packet_sha256=l.packet_sha256 FROM frame_recording_links l WHERE l.frame_id=m.frame_id AND m.job_id=?",id);checks.add("individual_frame_packet_proof_cannot_be_rebound");
        var restricted=new FrameRecordings(db,scope(db,"recording-04"),natives,images,manager,true);rejected(()->new SessionMediaInputs(db,restricted).metadata(frame,(String)first.get("manifest_sha256"),true),"recording_unavailable");checks.add("camera_scope_before_media_creation");
        db.update("UPDATE media_production_jobs SET attempts=0,next_attempt_at=clock_timestamp()");
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(MediaJobController.class,()->new MediaJobController(jobs,search,"verification-only-media-worker"));context.register(DelegatingWebFluxConfiguration.class);context.refresh();
            var handler=new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build());var ssl=SslContextBuilder.forServer(Path.of("/verification/tls.crt").toFile(),Path.of("/verification/tls.key").toFile()).build();
            var server=HttpServer.create().host("127.0.0.1").port(18080).secure(spec->spec.sslContext(ssl)).handle(handler).bindNow();
            try{
                Files.writeString(root.resolve("ready.json"),"{\"ready\":true}");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(420);
                while(db.queryForObject("SELECT count(*) FROM media_production_jobs WHERE state='completed'",Integer.class)<4){if(db.queryForObject("SELECT count(*) FROM media_production_jobs WHERE state='failed'",Integer.class)>0)throw new IllegalStateException("real session media failed: "+db.queryForList("SELECT state,last_error_code,attempts FROM media_production_jobs"));if(System.nanoTime()>deadline)throw new IllegalStateException("media consumer deadline");Thread.sleep(500);}
                checks.add("four_real_https_queue_jobs_and_encoding_children_completed");int sampleCount=0;var exports=new ArrayList<Map<String,Object>>();
                for(UUID identity:ids){var asset=clips.verify(identity,MediaClipFiles.Mode.MANIFEST,-1);check(MediaClipFiles.session(asset.manifest()),"wrong media schema");sampleCount+=((List<?>)asset.manifest().get("samples")).size();var saved=db.queryForList("SELECT * FROM media_production_jobs WHERE job_id=?",identity).getFirst();var complete=new MediaJobs.Complete(identity,(String)saved.get("lease_owner"),((Number)saved.get("lease_generation")).longValue(),(String)saved.get("input_sha256"),(String)saved.get("manifest_sha256"));check(Boolean.TRUE.equals(jobs.complete(complete).get("completed")),"completion replay failed");check(!jobs.fail(identity,complete.workerId(),complete.generation(),"media_timeout"),"late failure replaced success");
                    var input=(Map<String,Object>)asset.manifest().get("session_input");var anchor=candidates.stream().filter(row->row.get("manifest_sha256").equals(input.get("recording_manifest_sha256"))).findFirst().orElseThrow();
                    var export=new LinkedHashMap<String,Object>();export.put("job",jobs.read(identity));export.put("manifest",asset.manifest());export.put("detail",Map.of("frame_id",anchor.get("frame_id"),"camera_id",anchor.get("camera_id"),"video",recordings.hints(UUID.fromString((String)anchor.get("frame_id")))));exports.add(export);
                }
                check(db.queryForObject("SELECT count(*) FROM recorded_media_clips",Integer.class)==4,"duplicate derivative registry");checks.add("registered_v2_manifests_samples_and_late_completion_idempotency");
                var actual=recordings.playback(frame,false).orElseThrow();Path original=actual.path(),hidden=original.resolveSibling("source-retained-for-check.mp4");Files.move(original,hidden);try{clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1);rejected(()->recordings.playback(frame,true),"recording_file_unavailable");}finally{Files.move(hidden,original);}checks.add("registered_derivative_self_contained_after_native_file_missing");
                UUID sourceId=sources.get(UUID.fromString((String)first.get("task_id")));db.update("UPDATE sources SET source_time_origin='verified' WHERE source_id=?",sourceId);rejected(()->clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1),"media_source_changed");db.update("UPDATE sources SET source_time_origin='unknown' WHERE source_id=?",sourceId);checks.add("source_fingerprint_change_invalidates_derivative_reads");
                db.update("UPDATE frames SET analysis_state='failed' WHERE frame_id IN(SELECT frame_id FROM media_job_frames WHERE job_id=?)",id);rejected(()->clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1),"media_session_unavailable");clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1,false);db.update("UPDATE frames SET analysis_state='completed' WHERE frame_id IN(SELECT frame_id FROM media_job_frames WHERE job_id=?)",id);checks.add("public_read_rechecks_current_frame_state_independently_of_worker_reads");
                check(db.queryForObject("SELECT count(*) FROM observations",Long.class)==0&&db.queryForObject("SELECT count(*) FROM analysis_jobs",Long.class)==0&&db.queryForObject("SELECT count(*) FROM index_outbox",Long.class)==0,"media test created semantics");checks.add("no_semantic_observations_ai_jobs_or_index_events");
                Files.writeString(root.resolve("done.json"),"{\"result\":\"passed\"}");System.out.println("SESSION_MEDIA_READY="+JSON.writeValueAsString(Map.of("exports",exports)));System.out.flush();
                long exportDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);while(!Files.exists(root.resolve("export.done"))&&System.nanoTime()<exportDeadline)Thread.sleep(200);check(Files.exists(root.resolve("export.done")),"owned media export was not acknowledged");
                System.out.println("SESSION_MEDIA_CHECK_RESULT="+JSON.writeValueAsString(Map.of("result","passed","checks",checks,"real_sources",4,"real_jobs",4,"frame_links",8,"registered_clips",4,"real_samples",sampleCount,"new_model_requests",0,"https_worker",true,"private_completion_fixture_only",true)));
            }finally{server.disposeNow();}
        }
    }
}
