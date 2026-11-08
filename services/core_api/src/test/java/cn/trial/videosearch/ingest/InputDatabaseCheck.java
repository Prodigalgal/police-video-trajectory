package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real four-point JPEGs and PG, with faults restricted to private copies. No inference. */
@SuppressWarnings("unchecked")
public final class InputDatabaseCheck {
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void rejected(Runnable operation,String code){
        try{operation.run();throw new IllegalStateException("expected rejection: "+code);}
        catch(InputRejected error){check(code.equals(error.code),"rejection differs: "+error.code);}
    }
    private static SearchOrchestrator search(JdbcTemplate db,String cameras){return new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","disabled","https://disabled.invalid","/database-ca/ca.crt","disabled","disabled","disabled",cameras,"police-video-observations-input-check");}
    private static AnalysisInputs.Create command(UUID request,Map<String,Object> observation,String digest,String purpose){return new AnalysisInputs.Create(request,(UUID)observation.get("observation_id"),((Number)observation.get("record_version")).longValue(),digest,purpose,null);}
    public static void main(String[] args)throws Exception{
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");check(schema!=null&&schema.matches("queue_check_[0-9]{8}t[0-9]{6}z_[0-9a-f]{8}"),"private schema required");
        var source=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));
        var db=new JdbcTemplate(source);check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema binding differs");
        var manager=new DataSourceTransactionManager(source);var checks=new ArrayList<String>();
        var observations=db.queryForList("SELECT DISTINCT ON(camera_id) observation_id,frame_id,camera_id,record_version FROM observations WHERE NOT deleted ORDER BY camera_id,frame_id,object_ordinal");
        check(observations.size()==4,"four existing points required");
        Path temporary=Files.createTempDirectory("input-jpegs-");var original=new EvidenceFiles("/evidence");
        for(var record:db.queryForList("SELECT frame_id,evidence_ref FROM frames")){
            UUID frame=(UUID)record.get("frame_id");var image=original.read(frame,(String)record.get("evidence_ref"));check(image!=null,"registered real JPEG missing");
            Files.write(temporary.resolve(frame.toString().replace("-","")+".jpg"),image.bytes());
        }
        var files=new EvidenceFiles(temporary.toString());var search=search(db,"recording-01,recording-02,recording-03,recording-04");
        var temporal=new TemporalEvidence(db,search);var inputs=new AnalysisInputs(db,temporal,files,search,manager);
        long oldObservations=db.queryForObject("SELECT count(*) FROM observations",Long.class),oldModules=db.queryForObject("SELECT count(*) FROM analysis_modules",Long.class);
        check(db.queryForObject("SELECT count(*) FROM analysis_inputs",Long.class)==0&&db.queryForObject("SELECT count(*) FROM analysis_input_requests",Long.class)==0,"migration created inputs");checks.add("additive_migration_creates_no_work");
        var inputIds=new ArrayList<UUID>();var digests=new ArrayList<String>();
        for(var observation:observations){
            UUID id=(UUID)observation.get("observation_id"),frame=(UUID)observation.get("frame_id");
            var context=temporal.context(id,((Number)observation.get("record_version")).longValue(),TemporalWindow.defaults());
            check(!context.segment().eligible(),"retained sample unexpectedly contains a dense sequence");
            for(var neighbor:context.frames())for(String key:List.of("camera_id","task_id","scene_run_id","media_epoch"))check(Objects.equals(context.anchor().frame().view().get(key),neighbor.view().get(key)),"context crossed source boundary");
            var image=files.read(frame,context.anchor().frame().reference());digests.add(image.sha256());
            UUID request=UUID.randomUUID();var command=command(request,observation,image.sha256(),"single_frame");var saved=inputs.create(command,"reader");UUID input=UUID.fromString((String)saved.get("input_id"));inputIds.add(input);
            check(saved.get("creation_invoked_inference").equals(false),"save invoked inference");
            check(saved.get("input_id").equals(inputs.create(command,"reader").get("input_id")),"lost response replay duplicated input");
            check(saved.get("input_id").equals(inputs.create(command(UUID.randomUUID(),observation,image.sha256(),"single_frame"),"reader").get("input_id")),"same content duplicated input");
            var verified=inputs.verify(input,frame);check(verified.image().sha256().equals(image.sha256()),"worker image differs");
            String manifest=verified.payload();check(!manifest.contains("evidence:")&&!manifest.contains("source_ref")&&!manifest.contains("/evidence")&&!manifest.contains("rtsp://"),"manifest leaked storage reference");
            check(verified.sha256().equals(saved.get("manifest_sha256")),"manifest JSONB roundtrip changed digest");
            rejected(()->inputs.create(command(request,observation,"b".repeat(64),"single_frame"),"reader"),"input_request_conflict");
            rejected(()->inputs.create(command(request,observation,image.sha256(),"single_frame"),"another-reader"),"input_request_conflict");
            rejected(()->inputs.create(command(UUID.randomUUID(),observation,image.sha256(),"frame_sequence"),"reader"),"input_sequence_unavailable");
        }
        checks.addAll(List.of("four_real_points_context_preserves_lineage","sparse_real_frames_reject_sequence","four_real_single_frame_snapshots","request_replay_is_idempotent","same_actor_content_deduplicates","request_conflict_rejected","jsonb_roundtrip_preserves_digest","worker_reads_verified_real_bytes","manifest_has_no_storage_refs"));
        var first=observations.getFirst();UUID observation=(UUID)first.get("observation_id"),frame=(UUID)first.get("frame_id"),input=inputIds.getFirst();String digest=digests.getFirst();
        UUID concurrent=UUID.randomUUID();var gate=new CountDownLatch(1);var results=new ArrayList<Future<Map<String,Object>>>();
        try(var pool=Executors.newFixedThreadPool(8)){
            for(int number=0;number<8;number++)results.add(pool.submit(()->{gate.await();return inputs.create(command(concurrent,first,digest,"single_frame"),"parallel-reader");}));
            gate.countDown();var unique=new HashSet<Object>();for(var result:results)unique.add(result.get(45,TimeUnit.SECONDS).get("input_id"));check(unique.size()==1,"concurrent retry duplicated input");
        }
        check(db.queryForObject("SELECT count(*) FROM analysis_input_requests WHERE request_id=?",Long.class,concurrent)==1,"concurrent request duplicated receipt");checks.add("eight_concurrent_replays_save_once");
        var page=inputs.list(observation,null,1,null);check(Boolean.TRUE.equals(page.get("has_more")),"pagination missed input");
        var second=inputs.list(observation,null,1,UUID.fromString((String)page.get("next_cursor")));
        check(!((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("input_id").equals(((Map<?,?>)((List<?>)second.get("items")).getFirst()).get("input_id")),"input pages overlap");checks.add("bounded_pagination_has_no_overlap");
        var restricted=search(db,"recording-04");var restrictedTemporal=new TemporalEvidence(db,restricted);var hidden=new AnalysisInputs(db,restrictedTemporal,files,restricted,manager);
        rejected(()->hidden.read(input),"input_unavailable");rejected(()->hidden.list(observation,null,20,null),"observation_unavailable");rejected(()->restrictedTemporal.context(observation,null,TemporalWindow.defaults()),"observation_unavailable");checks.add("point_scope_applies_to_context_metadata_and_pixels");
        rejected(()->inputs.verify(input,(UUID)observations.get(1).get("frame_id")),"input_frame_unavailable");checks.add("worker_cannot_read_another_input_frame");
        db.update("UPDATE observations SET record_version=record_version+1 WHERE observation_id=?",observation);
        check(inputs.read(input).get("source_status").equals("stale"),"stale metadata not marked");rejected(()->inputs.verify(input,null),"observation_revision_conflict");
        db.update("UPDATE observations SET record_version=record_version-1 WHERE observation_id=?",observation);checks.add("updated_observation_blocks_consumption");
        db.update("UPDATE observations SET deleted=true WHERE observation_id=?",observation);rejected(()->inputs.read(input),"input_unavailable");rejected(()->inputs.verify(input,null),"input_unavailable");
        db.update("UPDATE observations SET deleted=false WHERE observation_id=?",observation);checks.add("withdrawal_blocks_metadata_and_export");
        db.update("UPDATE capture_outbox SET relative_media_ms=relative_media_ms+1 WHERE frame_id=?",frame);rejected(()->inputs.verify(input,null),"input_source_changed");
        db.update("UPDATE capture_outbox SET relative_media_ms=relative_media_ms-1 WHERE frame_id=?",frame);checks.add("changed_capture_clock_blocks_consumption");
        Path imagePath=temporary.resolve(frame.toString().replace("-","")+".jpg");byte[] retained=Files.readAllBytes(imagePath);
        UUID other=(UUID)observations.get(1).get("frame_id");Files.copy(temporary.resolve(other.toString().replace("-","")+".jpg"),imagePath,StandardCopyOption.REPLACE_EXISTING);
        rejected(()->inputs.verify(input,null),"input_evidence_changed");Files.write(imagePath,retained);checks.add("changed_real_jpeg_blocks_consumption");
        Files.delete(imagePath);rejected(()->inputs.verify(input,null),"input_evidence_unavailable");Files.write(imagePath,retained);checks.add("missing_jpeg_fails_closed");
        String savedManifest=db.queryForObject("SELECT manifest::text FROM analysis_inputs WHERE input_id=?",String.class,input);
        db.update("UPDATE analysis_inputs SET manifest=jsonb_set(manifest,'{purpose}','\"frame_sequence\"'::jsonb) WHERE input_id=?",input);rejected(()->inputs.verify(input,null),"input_manifest_changed");
        db.update("UPDATE analysis_inputs SET manifest=?::jsonb WHERE input_id=?",savedManifest,input);checks.add("tampered_manifest_rejected");
        // Legacy branch is a metadata fault in the private copy, still using the exact original JPEG.
        db.execute("CREATE TABLE restore_captures AS SELECT * FROM capture_outbox");db.execute("CREATE TABLE restore_targets AS SELECT * FROM detected_targets");db.execute("CREATE TABLE restore_modules AS SELECT * FROM analysis_modules");
        db.update("DELETE FROM analysis_modules WHERE frame_id=?",frame);db.update("DELETE FROM detected_targets WHERE frame_id=?",frame);db.update("DELETE FROM capture_outbox WHERE frame_id=?",frame);
        var legacy=inputs.create(command(UUID.randomUUID(),first,digest,"single_frame"),"legacy-check");UUID legacyId=UUID.fromString((String)legacy.get("input_id"));
        var legacyVerified=inputs.verify(legacyId,frame);check(((Map<?,?>)((List<?>)legacyVerified.manifest().get("frames")).getFirst()).get("capture_integrity").equals("legacy_without_digest"),"legacy capture integrity fabricated");
        check(((Map<?,?>)legacyVerified.manifest().get("target_hint")).get("status").equals("unavailable"),"legacy target box fabricated");checks.add("legacy_binds_current_bytes_without_invented_digest_or_box");
        db.update("INSERT INTO capture_outbox SELECT * FROM restore_captures WHERE frame_id=?",frame);db.update("INSERT INTO detected_targets SELECT * FROM restore_targets WHERE frame_id=?",frame);db.update("INSERT INTO analysis_modules SELECT * FROM restore_modules WHERE frame_id=?",frame);
        inputs.verify(input,frame);check(oldObservations==db.queryForObject("SELECT count(*) FROM observations",Long.class)&&oldModules==db.queryForObject("SELECT count(*) FROM analysis_modules",Long.class),"existing semantics changed");
        check(db.queryForObject("SELECT count(*) FROM analysis_result_sources",Long.class)==0&&db.queryForObject("SELECT count(*) FROM analysis_module_jobs",Long.class)==0&&db.queryForObject("SELECT count(*) FROM index_outbox",Long.class)==0,"input save fabricated analysis or index work");checks.add("old_semantics_preserved_and_no_inference_or_index_jobs_created");
        var fixtures=new ArrayList<Map<String,Object>>();var details=new ObservationDetails(db,search,files,new FrameRecordings(db,search,new StreamRecordingFiles("/recordings-live"),files,manager,false),"/recordings");
        for(int number=0;number<observations.size();number++){
            var observed=observations.get(number);UUID identity=(UUID)observed.get("observation_id");UUID savedInput=inputIds.get(number);
            fixtures.add(Map.of("detail",details.read(identity).orElseThrow(),"context",temporal.context(identity,null,TemporalWindow.defaults()).view(),"input",inputs.read(savedInput),"manifest",inputs.verify(savedInput,null).payload()));
        }
        System.out.println("INPUT_UI_FIXTURE="+new ObjectMapper().writeValueAsString(fixtures));
        System.out.println("INPUT_CHECK_RESULT="+new ObjectMapper().writeValueAsString(Map.of("result","passed","checks",checks,"real_points",4,"new_model_requests",0,"dense_real_sequence_verified",false,"private_copy_faults_only",true)));
    }
}
