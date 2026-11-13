package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("unchecked")
public final class MediaDatabaseCheck {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void rejected(Runnable operation,String code){try{operation.run();throw new IllegalStateException("expected rejection: "+code);}catch(InputRejected error){check(code.equals(error.code),"rejection differs: "+error.code);}}
    private static SearchOrchestrator search(JdbcTemplate db,String cameras){return new SearchOrchestrator(db,"https://disabled.invalid","/database-ca/ca.crt","disabled","https://disabled.invalid","/database-ca/ca.crt","disabled","disabled","disabled",cameras,"police-video-observations-media-check",new cn.trial.videosearch.evidence.ReviewedPlateFacts(db,false));}
    private static MediaClips.Register command(Map<String,Object> record){return new MediaClips.Register(UUID.fromString((String)record.get("clip_id")),(String)record.get("manifest_sha256"),"media-check");}
    public static void main(String[] args)throws Exception{
        String schema=System.getenv("EVAL_QUEUE_SCHEMA");check(schema!=null&&schema.matches("queue_check_[0-9]{8}t[0-9]{6}z_[0-9a-f]{8}"),"private schema required");
        var source=new DriverManagerDataSource(System.getenv("DATABASE_URL")+"&currentSchema="+schema,System.getenv("DATABASE_USER"),System.getenv("DATABASE_PASSWORD"));
        var db=new JdbcTemplate(source);check(schema.equals(db.queryForObject("SELECT current_schema()",String.class)),"schema differs");
        var manager=new DataSourceTransactionManager(source);var search=search(db,"recording-01,recording-02,recording-03,recording-04");
        Path copied=Files.createTempDirectory("media-check-copy-");
        try(var files=Files.walk(Path.of("/tmp/media-clips"))){for(Path original:files.toList()){
            Path target=copied.resolve(Path.of("/tmp/media-clips").relativize(original).toString());
            if(Files.isDirectory(original))Files.createDirectories(target);else Files.copy(original,target);
        }}
        var files=new MediaClipFiles(copied.toString(),"/recordings");var clips=new MediaClips(db,search,files,manager);var checks=new ArrayList<String>();
        List<Map<String,Object>> cases=JSON.readValue(Files.readString(Path.of("/verification/cases.json")),List.class);
        check(cases.size()==5,"five clips from four points required");check(db.queryForObject("SELECT count(*) FROM recorded_media_clips",Long.class)==0,"migration fabricated assets");checks.add("additive_registry_creates_no_assets");
        var registered=new ArrayList<Map<String,Object>>();
        for(var sample:cases){
            UUID id=UUID.fromString((String)sample.get("clip_id"));Path folder=copied.resolve(id.toString().replace("-",""));
            var manifest=JSON.readValue(Files.readString(folder.resolve("manifest.json")),Map.class);var encoded=CanonicalJson.encode(manifest,64*1024);
            var record=new LinkedHashMap<String,Object>(sample);record.put("manifest_sha256",encoded.sha256());registered.add(record);
            var receipt=clips.register(command(record));check(receipt.get("clip_id").equals(id.toString())&&receipt.get("inference_invoked").equals(false),"registration differs");
            var asset=clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1);check(asset.payload().equals(encoded.payload()),"canonical JSON changed across runtimes");
            var image=clips.verify(id,MediaClipFiles.Mode.SAMPLE,0).sample();check(image!=null&&image.width()<=1280,"genuine sample unavailable");
            check(clips.verify(id,MediaClipFiles.Mode.VIDEO,-1).videoBytes()==Files.size(folder.resolve("clip.mp4")),"video bytes differ");
            check(clips.register(command(record)).get("manifest_sha256").equals(encoded.sha256()),"registration replay changed content");
        }
        check(db.queryForObject("SELECT count(*) FROM recorded_media_clips",Long.class)==5,"registry duplicated assets");
        checks.addAll(List.of("four_original_recordings_verified","five_real_clips_registered","python_java_canonical_digest_agrees","real_jpeg_dimensions_and_digests","real_mp4_digest_and_range_source","registration_ack_replay_is_idempotent"));
        var first=registered.getFirst();UUID id=UUID.fromString((String)first.get("clip_id")),sourceId=UUID.fromString((String)first.get("source_id"));
        var detached=new MediaClips(db,search,new MediaClipFiles(copied.toString(),"/tmp/missing-source-catalog"),manager);
        check(detached.register(command(first)).get("clip_id").equals(id.toString()),"registered replay unexpectedly reloaded original video");checks.add("ack_replay_uses_retained_asset_without_original_recording");
        db.update("DELETE FROM recorded_media_clips WHERE clip_id=?",id);
        try(var pool=Executors.newFixedThreadPool(8)){
            var requests=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<8;i++)requests.add(pool.submit(()->clips.register(command(first))));
            for(var request:requests)check(request.get(90,TimeUnit.SECONDS).get("clip_id").equals(id.toString()),"concurrent receipt differs");
        }
        check(db.queryForObject("SELECT count(*) FROM recorded_media_clips WHERE clip_id=?",Long.class,id)==1,"concurrent duplicates created");checks.add("eight_concurrent_registrations_preserve_one_asset");
        var page=clips.list("recording-01",1,null);check(Boolean.TRUE.equals(page.get("has_more")),"media page cursor missing");
        var next=clips.list("recording-01",1,UUID.fromString((String)page.get("next_cursor")));
        check(!((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("clip_id").equals(((Map<?,?>)((List<?>)next.get("items")).getFirst()).get("clip_id")),"media pages overlap");checks.add("camera_cursor_pagination");
        var hidden=new MediaClips(db,search(db,"recording-04"),files,manager);rejected(()->hidden.read(id),"media_unavailable");rejected(()->hidden.verify(id,MediaClipFiles.Mode.VIDEO,-1),"media_unavailable");checks.add("camera_scope_protects_metadata_video_and_samples");
        rejected(()->clips.verify(id,MediaClipFiles.Mode.SAMPLE,31),"media_sample_unavailable");checks.add("sample_index_is_bounded");
        Path folder=copied.resolve(id.toString().replace("-","")),image=folder.resolve("sample-000.jpg"),video=folder.resolve("clip.mp4");
        byte[] savedImage=Files.readAllBytes(image),savedVideo=Files.readAllBytes(video);UUID other=UUID.fromString((String)registered.get(1).get("clip_id"));
        Files.copy(copied.resolve(other.toString().replace("-","")).resolve("sample-000.jpg"),image,StandardCopyOption.REPLACE_EXISTING);
        rejected(()->clips.verify(id,MediaClipFiles.Mode.SAMPLE,0),"media_evidence_changed");Files.write(image,savedImage);checks.add("changed_real_jpeg_is_rejected");
        byte[] modified=savedVideo.clone();modified[0]^=1;Files.write(video,modified);rejected(()->clips.verify(id,MediaClipFiles.Mode.VIDEO,-1),"media_evidence_changed");Files.write(video,savedVideo);checks.add("changed_mp4_is_rejected");
        Path later=folder.resolve("sample-001.jpg");byte[] retained=Files.readAllBytes(later);Files.delete(later);
        check(clips.verify(id,MediaClipFiles.Mode.SAMPLE,0).sample()!=null,"sample read reloaded unrelated pixels");rejected(()->clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1),"media_evidence_unavailable");Files.write(later,retained);checks.add("manifest_checks_all_pixels_sample_reads_only_its_pixels");
        db.update("UPDATE sources SET source_time_origin='verified' WHERE source_id=?",sourceId);rejected(()->clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1),"media_source_changed");
        db.update("UPDATE sources SET source_time_origin='unknown' WHERE source_id=?",sourceId);checks.add("source_clock_change_invalidates_registration");
        String saved=db.queryForObject("SELECT manifest::text FROM recorded_media_clips WHERE clip_id=?",String.class,id);
        db.update("UPDATE recorded_media_clips SET manifest=jsonb_set(manifest,'{sample_period_ms}','5000'::jsonb) WHERE clip_id=?",id);rejected(()->clips.read(id),"media_manifest_changed");
        db.update("UPDATE recorded_media_clips SET manifest=?::jsonb WHERE clip_id=?",saved,id);checks.add("tampered_database_manifest_rejected");
        var asset=clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1);
        var wrongOriginal=new MediaClipFiles.Assets(asset.manifest(),asset.payload(),asset.sha256(),asset.sourceId(),asset.camera(),"0".repeat(64),asset.sourceBytes(),asset.video(),asset.videoBytes(),null);
        rejected(()->files.verifyRecording(wrongOriginal),"media_source_changed");checks.add("original_recording_digest_must_match");
        check(db.queryForObject("SELECT count(*) FROM observations",Long.class)==0&&db.queryForObject("SELECT count(*) FROM index_outbox",Long.class)==0&&db.queryForObject("SELECT count(*) FROM analysis_jobs",Long.class)==0,"media preparation fabricated semantics or work");checks.add("no_observations_index_events_or_model_jobs_created");
        System.out.println("MEDIA_CHECK_RESULT="+JSON.writeValueAsString(Map.of("result","passed","checks",checks,"real_points",4,"clips",5,"new_model_requests",0,"private_archive_catalog_only",true)));
    }
}
