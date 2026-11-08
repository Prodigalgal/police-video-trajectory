package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Service
public class FrameRecordings {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    private final JdbcTemplate db;private final SearchOrchestrator search;private final StreamRecordingFiles files;private final EvidenceFiles images;
    private final TransactionTemplate transaction;private final boolean enabled;
    public FrameRecordings(JdbcTemplate db,SearchOrchestrator search,StreamRecordingFiles files,EvidenceFiles images,PlatformTransactionManager manager,@Value("${VIDEO_LIVE_RECORDING_ENABLED:false}")boolean enabled){this.db=db;this.search=search;this.files=files;this.images=images;transaction=new TransactionTemplate(manager);this.enabled=enabled;}
    public record Register(UUID frameId,String payloadSha256,String namespace,UUID segmentId,String manifestSha256,Long sourcePtsUs,String packetSha256){}
    public record Playback(UUID segmentId,Path path,long bytes,long seekOffsetMs,String manifestSha256,Map<String,Object> manifest){}
    public record Reference(UUID frameId,UUID segmentId,String namespace,StreamRecordingFiles.Recording recording,Map<String,Object> capture,long seekOffsetMs,long sourcePtsUs,String packetSha256){}
    public boolean enabled(){return enabled;}
    public Map<String,Object> register(Register request){
        if(!enabled)throw new InputRejected(404,"recording_disabled");validate(request);
        var recording=files.read(request.namespace(),request.segmentId(),request.manifestSha256());
        var capture=capture(request.frameId(),recording);checkBinding(capture,recording,request);verifyImage(request.frameId(),capture,request.payloadSha256());
        transaction.executeWithoutResult(status->{
            db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","recording-frame:"+request.frameId());
            db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","recording-segment:"+request.segmentId());
            db.queryForList("SELECT c.frame_id FROM capture_outbox c JOIN processing_tasks t ON t.task_id=c.task_id JOIN sources s ON s.source_id=t.source_id WHERE c.frame_id=? FOR SHARE OF c,t,s",request.frameId());
            db.queryForList("SELECT frame_id FROM frames WHERE frame_id=? FOR SHARE",request.frameId());
            var current=capture(request.frameId(),recording);checkBinding(current,recording,request);
            var existing=db.queryForList("SELECT storage_namespace,manifest_sha256 FROM stream_recording_segments WHERE segment_id=?",request.segmentId());
            if(!existing.isEmpty()&&(!request.namespace().equals(existing.getFirst().get("storage_namespace"))||!request.manifestSha256().equals(existing.getFirst().get("manifest_sha256"))))throw new InputRejected(409,"recording_reference_conflict");
            if(existing.isEmpty())db.update("INSERT INTO stream_recording_segments(segment_id,storage_namespace,task_id,camera_id,scene_run_id,media_epoch,manifest,manifest_sha256) VALUES(?,?,?,?,?,?,?::jsonb,?)",request.segmentId(),request.namespace(),current.get("task_id"),current.get("camera_id"),current.get("scene_run_id"),current.get("media_epoch"),recording.payload(),request.manifestSha256());
            var links=db.queryForList("SELECT segment_id,payload_sha256,source_pts_us,packet_sha256 FROM frame_recording_links WHERE frame_id=?",request.frameId());
            if(!links.isEmpty()){
                var old=links.getFirst();if(!request.segmentId().equals(old.get("segment_id"))||!request.payloadSha256().equals(old.get("payload_sha256"))||request.sourcePtsUs()!=((Number)old.get("source_pts_us")).longValue()||!request.packetSha256().equals(old.get("packet_sha256")))throw new InputRejected(409,"recording_reference_conflict");
            }else{
                long offset=Math.round(Math.subtractExact(request.sourcePtsUs(),recording.basePtsUs())/1000.0);
                db.update("INSERT INTO frame_recording_links(frame_id,segment_id,payload_sha256,source_pts_us,packet_sha256,seek_offset_ms) VALUES(?,?,?,?,?,?)",request.frameId(),request.segmentId(),request.payloadSha256(),request.sourcePtsUs(),request.packetSha256(),offset);
                db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",recording.manifest().get("worker_id"),"link_frame_recording","recorded",request.frameId());
            }
        });
        return Map.of("recorded",true,"frame_id",request.frameId().toString(),"segment_id",request.segmentId().toString(),"manifest_sha256",request.manifestSha256());
    }
    private Map<String,Object> capture(UUID frame,StreamRecordingFiles.Recording recording){
        var rows=db.queryForList("""
            SELECT c.frame_id,c.task_id,c.worker_id,c.lease_generation,c.payload_sha256,c.evidence_ref,c.camera_id,c.scene_run_id,c.media_epoch,c.relative_media_ms,
            s.source_ref,f.analysis_state,s.source_id,s.source_type,s.source_time_origin FROM capture_outbox c LEFT JOIN frames f ON f.frame_id=c.frame_id
            JOIN processing_tasks t ON t.task_id=c.task_id JOIN sources s ON s.source_id=t.source_id
            WHERE c.frame_id=? AND c.camera_id=s.camera_id AND (f.frame_id IS NULL OR f.task_id=c.task_id AND f.evidence_ref=c.evidence_ref)
            """,frame);
        if(rows.isEmpty()){
            UUID task=UUID.fromString((String)recording.manifest().get("task_id"));
            var tasks=db.queryForList("SELECT state,lease_generation,lease_owner,lease_expires_at>clock_timestamp() AS alive FROM processing_tasks WHERE task_id=?",task);
            if(tasks.size()==1&&"active".equals(tasks.getFirst().get("state"))&&Boolean.TRUE.equals(tasks.getFirst().get("alive"))&&Objects.equals(tasks.getFirst().get("lease_owner"),recording.manifest().get("worker_id"))&&((Number)tasks.getFirst().get("lease_generation")).longValue()==((Number)recording.manifest().get("task_generation")).longValue())throw new InputRejected(404,"recording_capture_pending");
            throw new InputRejected(410,"recording_capture_unregistered");
        }
        if(rows.size()!=1||!search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"recording_unavailable");return rows.getFirst();
    }
    private static void validate(Register request){
        if(request==null||request.frameId()==null||request.segmentId()==null||request.sourcePtsUs()==null||request.payloadSha256()==null||!request.payloadSha256().matches("[a-f0-9]{64}")||request.packetSha256()==null||!request.packetSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid recording request");
    }
    private static void checkBinding(Map<String,Object> capture,StreamRecordingFiles.Recording recording,Register reference){
        var source=recording.manifest();
        if(!capture.get("task_id").toString().equals(source.get("task_id"))||!capture.get("scene_run_id").toString().equals(source.get("scene_run_id"))||!capture.get("camera_id").equals(source.get("camera_id"))||!capture.get("worker_id").equals(source.get("worker_id"))
            ||((Number)capture.get("lease_generation")).longValue()!=((Number)source.get("task_generation")).longValue()||((Number)capture.get("media_epoch")).longValue()!=((Number)source.get("media_epoch")).longValue()
            ||!CanonicalJson.sha256((String)capture.get("source_ref")).equals(source.get("source_ref_sha256"))||!reference.payloadSha256().equals(capture.get("payload_sha256"))||!reference.packetSha256().equals(recording.packets().get(reference.sourcePtsUs())))throw new InputRejected(409,"recording_source_conflict");
        try{
            long relative=Math.max(0,Math.round(Math.subtractExact(reference.sourcePtsUs(),((Number)source.get("origin_pts_us")).longValue())/1000.0));
            long difference=Math.subtractExact(relative,((Number)capture.get("relative_media_ms")).longValue());
            if(difference < -1 || difference > 1)throw new InputRejected(409,"recording_clock_conflict");
        }catch(ArithmeticException invalidClock){throw new InputRejected(409,"recording_clock_conflict");}
    }
    private void verifyImage(UUID frame,Map<String,Object> capture,String expected){
        try{var image=images.read(frame,(String)capture.get("evidence_ref"));if(image==null||!image.sha256().equals(expected))throw new InputRejected(409,"recording_pixels_changed");}
        catch(IOException error){throw new InputRejected(422,"recording_image_unavailable");}
    }
    @SuppressWarnings("unchecked")
    public Optional<Reference> reference(UUID frame,boolean publicView){
        if(!enabled)return Optional.empty();
        var rows=db.queryForList("""
            SELECT l.segment_id,l.payload_sha256,l.source_pts_us,l.packet_sha256,l.seek_offset_ms,s.storage_namespace,s.manifest_sha256,s.manifest::text AS manifest_text
            FROM frame_recording_links l JOIN stream_recording_segments s USING(segment_id) WHERE l.frame_id=?
            """,frame);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        var stored=JSON.readValue((String)row.get("manifest_text"),Object.class);
        if(!(stored instanceof Map<?,?> manifest))throw new InputRejected(409,"recording_manifest_changed");
        var recording=files.describe((String)row.get("storage_namespace"),(UUID)row.get("segment_id"),(String)row.get("manifest_sha256"),(Map<String,Object>)manifest);
        var capture=capture(frame,recording);
        if(publicView&&!"completed".equals(capture.get("analysis_state")))return Optional.empty();
        if(publicView&&db.queryForObject("SELECT EXISTS(SELECT 1 FROM observations WHERE frame_id=?) AND NOT EXISTS(SELECT 1 FROM observations WHERE frame_id=? AND NOT deleted)",Boolean.class,frame,frame))return Optional.empty();
        var reference=new Register(frame,(String)row.get("payload_sha256"),(String)row.get("storage_namespace"),(UUID)row.get("segment_id"),(String)row.get("manifest_sha256"),((Number)row.get("source_pts_us")).longValue(),(String)row.get("packet_sha256"));
        checkBinding(capture,recording,reference);
        long offset=Math.round(Math.subtractExact(reference.sourcePtsUs(),recording.basePtsUs())/1000.0);
        if(offset!=((Number)row.get("seek_offset_ms")).longValue())throw new InputRejected(409,"recording_clock_conflict");
        return Optional.of(new Reference(frame,reference.segmentId(),reference.namespace(),recording,capture,offset,reference.sourcePtsUs(),reference.packetSha256()));
    }
    public Optional<Playback> playback(UUID frame,boolean publicView){
        return reference(frame,publicView).map(reference->{
            var recording=files.read(reference.namespace(),reference.segmentId(),reference.recording().sha256());
            verifyImage(frame,reference.capture(),(String)reference.capture().get("payload_sha256"));
            return new Playback(reference.segmentId(),recording.path(),recording.bytes(),reference.seekOffsetMs(),recording.sha256(),recording.manifest());
        });
    }
    public Map<String,Object> hints(UUID frame){
        try{return playback(frame,true).<Map<String,Object>>map(p->Map.of("available",true,"reason","same_session_recording","kind","session_segment","recording_id",p.segmentId().toString(),"seek_offset_ms",p.seekOffsetMs(),"recording_manifest_sha256",p.manifestSha256(),"duration_ms",((Number)p.manifest().get("duration_us")).longValue()/1000,"normalization_eligible",((Number)p.manifest().get("duration_us")).longValue()>=2000000)).orElseGet(()->Map.of("available",false,"reason","no_registered_recording"));}
        catch(InputRejected invalid){return Map.of("available",false,"reason","recording_unverified");}
    }
}
