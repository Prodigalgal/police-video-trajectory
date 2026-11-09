package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real same-session files/captures; state faults stay in this private schema. */
@SuppressWarnings("unchecked")
public final class RecordingDatabaseCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void rejected(Runnable action,String code){try{action.run();throw new IllegalStateException("expected "+code);}catch(InputRejected error){check(code.equals(error.code),"rejection differs: "+error.code);}}
    private static SearchOrchestrator scope(JdbcTemplate db,String cameras){return new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","disabled","https://disabled.invalid","/database-ca/ca.crt","disabled","disabled","disabled",cameras,"police-video-observations-recording-check",new cn.trial.videosearch.evidence.ReviewedPlateFacts(db,false));}
    private static FrameRecordings.Register request(Map<String,Object> row){var r=(Map<String,Object>)row.get("recording");return new FrameRecordings.Register(UUID.fromString((String)row.get("frame_id")),(String)row.get("payload_sha256"),(String)r.get("namespace"),UUID.fromString((String)r.get("segment_id")),(String)row.get("manifest_sha256"),((Number)r.get("source_pts_us")).longValue(),(String)r.get("packet_sha256"));}
    public static void main(String[] args)throws Exception{
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");check(schema!=null&&schema.matches("queue_check_[0-9]{8}t[0-9]{6}z_[0-9a-f]{8}"),"private schema required");
        var dataSource=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));
        var db=new JdbcTemplate(dataSource);var manager=new DataSourceTransactionManager(dataSource);var tx=new TransactionTemplate(manager);tx.setTimeout(20);
        check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema mismatch");
        var images=new EvidenceFiles("/tmp/native-check/evidence");var files=new StreamRecordingFiles("/tmp/native-check/recordings");
        var search=scope(db,"recording-01,recording-02,recording-03,recording-04");var recordings=new FrameRecordings(db,search,files,images,manager,true);
        var targets=new TargetPipeline(db,null,null,2000,"/tmp/native-check/evidence",null);
        List<Map<String,Object>> candidates=JSON.readValue(Files.readString(Path.of("/tmp/native-check/candidates.json")),List.class);
        var cases=new LinkedHashMap<String,Map<String,Object>>();for(var row:candidates)cases.putIfAbsent(row.get("camera_id")+":"+row.get("source_type"),row);
        check(cases.size()==8,"four archive and four live sources required");var checks=new ArrayList<String>();
        for(var row:cases.values()){
            UUID source=UUID.randomUUID(),task=UUID.fromString((String)row.get("task_id")),frame=UUID.fromString((String)row.get("frame_id"));
            db.update("INSERT INTO sources(source_id,camera_id,source_type,source_ref,source_time_origin,enabled) VALUES(?,?,?,?,?,true)",source,row.get("camera_id"),row.get("source_type"),row.get("source_ref"),"unknown");
            db.update("INSERT INTO processing_tasks(task_id,source_id,task_type,state,lease_owner,lease_generation,lease_expires_at) VALUES(?,?,?,'active',?,1,clock_timestamp()+interval '20 minutes')",task,source,row.get("source_type"),row.get("worker_id"));
            var image=images.read(frame,"evidence:"+frame.toString().replace("-",""));check(image!=null,"real JPEG required");
            // No detection/semantic claims are fabricated by a recording test.
            var plan=Map.<String,Object>of("version","targets-v1","strategy","frame_batch","detection_state","unknown","image_width",image.width(),"image_height",image.height(),"targets",List.of());
            var capture=new TargetPipeline.Capture(frame,task,(String)row.get("worker_id"),1,"evidence:"+frame.toString().replace("-",""),(String)row.get("payload_sha256"),row.get("media_offset_ms")==null?null:((Number)row.get("media_offset_ms")).longValue(),null,UUID.fromString((String)row.get("scene_run_id")),((Number)row.get("media_epoch")).longValue(),((Number)row.get("relative_media_ms")).longValue(),plan);
            check(tx.execute(status->targets.register(capture))!=null,"capture registration failed");
            var command=request(row);check(Boolean.TRUE.equals(recordings.register(command).get("recorded")),"recording registration failed");
            check(recordings.register(command).equals(recordings.register(command)),"replay differs");
            check(recordings.playback(frame,false).orElseThrow().seekOffsetMs()>=0,"worker playback unavailable");
            check(recordings.playback(frame,true).isEmpty(),"unprocessed capture became public");
        }
        checks.add("eight_real_same_session_captures_and_idempotent_links_before_analysis");
        check(db.queryForObject("SELECT count(*) FROM frame_recording_links",Integer.class)==8,"duplicate links");
        var row=cases.get("recording-01:rtsp");var command=request(row);UUID frame=command.frameId(),task=UUID.fromString((String)row.get("task_id"));
        try(var pool=Executors.newFixedThreadPool(8)){
            var futures=new ArrayList<Future<Map<String,Object>>>();for(int n=0;n<8;n++)futures.add(pool.submit(()->recordings.register(command)));
            for(var future:futures)check(Boolean.TRUE.equals(future.get(45,TimeUnit.SECONDS).get("recorded")),"concurrent replay failed");
        }
        check(db.queryForObject("SELECT count(*) FROM audit_events WHERE action='link_frame_recording' AND request_id=?",Integer.class,frame)==1,"replay duplicated audit");checks.add("eight_concurrent_replays_preserve_one_link_and_audit");
        rejected(()->recordings.register(new FrameRecordings.Register(frame,"0".repeat(64),command.namespace(),command.segmentId(),command.manifestSha256(),command.sourcePtsUs(),command.packetSha256())),"recording_source_conflict");
        rejected(()->recordings.register(new FrameRecordings.Register(frame,command.payloadSha256(),command.namespace(),command.segmentId(),command.manifestSha256(),command.sourcePtsUs()+1,command.packetSha256())),"recording_source_conflict");checks.add("wrong_jpeg_or_source_packet_cannot_bind");
        db.update("UPDATE capture_outbox SET media_epoch=media_epoch+1 WHERE frame_id=?",frame);rejected(()->recordings.register(command),"recording_source_conflict");db.update("UPDATE capture_outbox SET media_epoch=media_epoch-1 WHERE frame_id=?",frame);checks.add("cross_epoch_rejected");
        db.update("UPDATE capture_outbox SET relative_media_ms=relative_media_ms+100 WHERE frame_id=?",frame);rejected(()->recordings.register(command),"recording_clock_conflict");db.update("UPDATE capture_outbox SET relative_media_ms=relative_media_ms-100 WHERE frame_id=?",frame);checks.add("relative_time_must_match_actual_source_pts");
        var missing=new FrameRecordings.Register(UUID.randomUUID(),command.payloadSha256(),command.namespace(),command.segmentId(),command.manifestSha256(),command.sourcePtsUs(),command.packetSha256());
        rejected(()->recordings.register(missing),"recording_capture_pending");db.update("UPDATE processing_tasks SET state='completed' WHERE task_id=?",task);
        rejected(()->recordings.register(missing),"recording_capture_unregistered");check(Boolean.TRUE.equals(recordings.register(command).get("recorded")),"committed capture lost on task completion");checks.add("pending_and_expired_captures_distinguished_registered_capture_survives_completion");
        db.update("UPDATE processing_tasks SET lease_owner='new-owner',lease_generation=2 WHERE task_id=?",task);check(Boolean.TRUE.equals(recordings.register(command).get("recorded")),"renewal invalidated immutable capture");checks.add("new_lease_does_not_rebind_old_capture");
        // Private completion state only; no observations, model results, or jobs.
        db.update("INSERT INTO frames(frame_id,task_id,lease_generation,evidence_ref,source_time_origin,received_at,analysis_state) SELECT frame_id,task_id,lease_generation,evidence_ref,source_time_origin,registered_at,'completed' FROM capture_outbox WHERE frame_id=?",frame);
        var asset=recordings.playback(frame,true).orElseThrow();check("session_segment".equals(recordings.hints(frame).get("kind")),"detail hints missing");checks.add("public_playback_requires_completed_frame_and_exposes_segment_offset_only");
        db.update("UPDATE frame_recording_links SET seek_offset_ms=seek_offset_ms+100 WHERE frame_id=?",frame);rejected(()->recordings.playback(frame,true),"recording_clock_conflict");db.update("UPDATE frame_recording_links SET seek_offset_ms=seek_offset_ms-100 WHERE frame_id=?",frame);checks.add("stored_seek_position_recomputed_from_source_clock");
        var restricted=new FrameRecordings(db,scope(db,"recording-04"),files,images,manager,true);rejected(()->restricted.playback(frame,true),"recording_unavailable");checks.add("camera_scope_checked_before_returning_media");
        byte[] bytes=Files.readAllBytes(asset.path());byte[] changed=bytes.clone();changed[0]^=1;Files.write(asset.path(),changed);rejected(()->recordings.playback(frame,true),"recording_pixels_changed");Files.write(asset.path(),bytes);checks.add("changed_video_rejected");
        Path jpeg=Path.of("/tmp/native-check/evidence",frame.toString().replace("-","")+".jpg");byte[] imageBytes=Files.readAllBytes(jpeg);byte[] other=Files.readAllBytes(Path.of("/tmp/native-check/evidence",request(cases.get("recording-02:rtsp")).frameId().toString().replace("-","")+".jpg"));
        Files.write(jpeg,other);rejected(()->recordings.playback(frame,true),"recording_pixels_changed");Files.write(jpeg,imageBytes);checks.add("changed_capture_pixels_rejected");
        String saved=db.queryForObject("SELECT manifest::text FROM stream_recording_segments WHERE segment_id=?",String.class,command.segmentId());
        db.update("UPDATE stream_recording_segments SET manifest=jsonb_set(manifest,'{width}','1'::jsonb) WHERE segment_id=?",command.segmentId());rejected(()->recordings.playback(frame,true),"recording_manifest_changed");db.update("UPDATE stream_recording_segments SET manifest=?::jsonb WHERE segment_id=?",saved,command.segmentId());checks.add("database_manifest_mutation_rejected");
        db.update("UPDATE sources SET source_ref='simulator:changed' WHERE source_id=(SELECT source_id FROM processing_tasks WHERE task_id=?)",task);rejected(()->recordings.playback(frame,true),"recording_source_conflict");db.update("UPDATE sources SET source_ref=? WHERE source_id=(SELECT source_id FROM processing_tasks WHERE task_id=?)",row.get("source_ref"),task);checks.add("source_reference_change_invalidates_playback");
        check(db.queryForObject("SELECT count(*) FROM observations",Long.class)==0&&db.queryForObject("SELECT count(*) FROM analysis_jobs",Long.class)==0&&db.queryForObject("SELECT count(*) FROM index_outbox",Long.class)==0,"recording test fabricated semantics or analysis work");checks.add("no_observations_model_jobs_or_index_events_created");
        System.out.println("RECORDING_CHECK_RESULT="+JSON.writeValueAsString(Map.of("result","passed","checks",checks,"real_sources",8,"frame_links",8,"new_model_requests",0,"private_completion_fixture_only",true)));
    }
}
