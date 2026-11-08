package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** A segment is one encoding input; frame associations retain their own packet proofs. */
@Service
public class SessionMediaInputs {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    public static final String PROFILE="session-h264-10fps-v1";
    public static final String VERSION="session-segment-input-v1";
    private static final int MAX_LINKS=128;
    private final JdbcTemplate db;private final FrameRecordings recordings;
    public SessionMediaInputs(JdbcTemplate db,FrameRecordings recordings){this.db=db;this.recordings=recordings;}
    public record Snapshot(Map<String,Object> descriptor,FrameRecordings.Reference reference){}
    public boolean enabled(){return recordings.enabled();}
    public Snapshot prepare(UUID frame,String expected,boolean publicView){
        var snapshot=metadata(frame,expected,publicView);
        var pixels=recordings.playback(frame,publicView).orElseThrow(SessionMediaInputs::unavailable);
        if(!expected.equals(pixels.manifestSha256()))throw changed();
        var current=metadata(frame,expected,publicView);
        if(!same(snapshot.descriptor(),current.descriptor())||snapshot.reference().sourcePtsUs()!=current.reference().sourcePtsUs()||!snapshot.reference().capture().get("payload_sha256").equals(current.reference().capture().get("payload_sha256")))throw changed();
        return current;
    }
    public Snapshot metadata(UUID frame,String expected,boolean publicView){
        if(expected==null||!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid recording digest");
        var reference=recordings.reference(frame,publicView).orElseThrow(SessionMediaInputs::unavailable);
        if(!expected.equals(reference.recording().sha256()))throw changed();
        return new Snapshot(descriptor(reference),reference);
    }
    private static Map<String,Object> descriptor(FrameRecordings.Reference reference){
        var nativeRecording=reference.recording();var manifest=nativeRecording.manifest();var capture=reference.capture();
        var value=new LinkedHashMap<String,Object>();value.putAll(Map.of("version",VERSION,"source_id",capture.get("source_id").toString(),"camera_id",capture.get("camera_id"),
            "source_fingerprint",MediaSourceIdentity.fingerprint(capture),"namespace",reference.namespace(),"segment_id",reference.segmentId().toString(),
            "recording_manifest_sha256",nativeRecording.sha256(),"source_sha256",((Map<?,?>)manifest.get("video")).get("sha256"),"source_bytes",nativeRecording.bytes()));
        value.putAll(Map.of("duration_us",nativeRecording.durationUs(),"task_id",manifest.get("task_id"),"task_generation",manifest.get("task_generation"),"scene_run_id",manifest.get("scene_run_id"),"media_epoch",manifest.get("media_epoch"),"base_pts_us",nativeRecording.basePtsUs()));
        validateDescriptor(value);return Map.copyOf(value);
    }
    public void lockFrame(UUID frame){
        db.queryForList("SELECT c.frame_id FROM capture_outbox c JOIN processing_tasks t ON t.task_id=c.task_id JOIN sources s ON s.source_id=t.source_id WHERE c.frame_id=? FOR SHARE OF c,t,s",frame);
        db.queryForList("SELECT frame_id FROM frames WHERE frame_id=? FOR SHARE",frame);
        db.queryForList("SELECT l.frame_id FROM frame_recording_links l JOIN stream_recording_segments s USING(segment_id) WHERE l.frame_id=? FOR SHARE OF l,s",frame);
        db.queryForList("SELECT observation_id FROM observations WHERE frame_id=? FOR SHARE",frame);
    }
    public FrameRecordings.Reference verifyFrame(UUID frame,Map<String,Object> expected,boolean publicView){
        validateDescriptor(expected);var reference=recordings.reference(frame,publicView).orElseThrow(SessionMediaInputs::unavailable);
        if(!same(expected,descriptor(reference)))throw changed();return reference;
    }
    public void attach(UUID job,Snapshot snapshot,boolean publicView){
        var expected=snapshot.reference();var current=verifyFrame(expected.frameId(),snapshot.descriptor(),publicView);
        if(current.sourcePtsUs()!=expected.sourcePtsUs()||!current.packetSha256().equals(expected.packetSha256())||!current.capture().get("payload_sha256").equals(expected.capture().get("payload_sha256")))throw changed();
        var rows=db.queryForList("SELECT * FROM media_job_frames WHERE job_id=? AND frame_id=?",job,current.frameId());
        if(!rows.isEmpty()){verifyLink(rows.getFirst(),current);return;}
        if(db.queryForObject("SELECT count(*) FROM media_job_frames WHERE job_id=?",Integer.class,job)>=MAX_LINKS)throw new InputRejected(429,"media_frame_limit");
        db.update("INSERT INTO media_job_frames(job_id,frame_id,recording_manifest_sha256,payload_sha256,source_pts_us,packet_sha256,seek_offset_ms) VALUES(?,?,?,?,?,?,?)",job,current.frameId(),current.recording().sha256(),current.capture().get("payload_sha256"),current.sourcePtsUs(),current.packetSha256(),current.seekOffsetMs());
    }
    public void verifyLinkedFrame(UUID job,UUID frame,Map<String,Object> descriptor,boolean publicView){
        var reference=verifyFrame(frame,descriptor,publicView);var rows=db.queryForList("SELECT * FROM media_job_frames WHERE job_id=? AND frame_id=?",job,frame);
        if(rows.size()!=1)throw unavailable();verifyLink(rows.getFirst(),reference);
    }
    public FrameRecordings.Reference verifyJob(UUID job,Map<String,Object> descriptor,boolean publicView){
        validateDescriptor(descriptor);
        var jobs=db.queryForList("SELECT source_id,camera_id,source_fingerprint,source_sha256,source_bytes,start_ms,end_ms,profile_version,input_kind,input_sha256,input_descriptor::text AS descriptor_text FROM media_production_jobs WHERE job_id=?",job);
        if(jobs.size()!=1)throw unavailable();var saved=jobs.getFirst();
        if(!"session".equals(saved.get("input_kind"))||!PROFILE.equals(saved.get("profile_version"))||!inputSha(descriptor).equals(saved.get("input_sha256"))||!descriptor.get("source_id").equals(saved.get("source_id").toString())
            ||!descriptor.get("camera_id").equals(saved.get("camera_id"))||!descriptor.get("source_fingerprint").equals(saved.get("source_fingerprint"))||!descriptor.get("source_sha256").equals(saved.get("source_sha256"))
            ||((Number)descriptor.get("source_bytes")).longValue()!=((Number)saved.get("source_bytes")).longValue()||((Number)saved.get("start_ms")).longValue()!=0||((Number)saved.get("end_ms")).longValue()!=endMs(descriptor))throw changed();
        Object raw=JSON.readValue((String)saved.get("descriptor_text"),Object.class);
        if(!(raw instanceof Map<?,?> map)||!CanonicalJson.encode(map,16384).sha256().equals(CanonicalJson.encode(descriptor,16384).sha256()))throw changed();
        var rows=db.queryForList("""
            SELECT m.* FROM media_job_frames m JOIN capture_outbox c ON c.frame_id=m.frame_id LEFT JOIN frames f ON f.frame_id=m.frame_id
            WHERE m.job_id=? AND c.payload_sha256=m.payload_sha256 AND (NOT ? OR (f.analysis_state='completed'
            AND NOT (EXISTS(SELECT 1 FROM observations o WHERE o.frame_id=m.frame_id) AND NOT EXISTS(SELECT 1 FROM observations o WHERE o.frame_id=m.frame_id AND NOT o.deleted))))
            ORDER BY m.frame_id LIMIT 1
            """,job,publicView);
        if(rows.isEmpty())throw unavailable();var row=rows.getFirst();var reference=verifyFrame((UUID)row.get("frame_id"),descriptor,publicView);verifyLink(row,reference);return reference;
    }
    public void verifyOriginal(UUID job,Map<String,Object> descriptor){
        var reference=verifyJob(job,descriptor,false);var actual=recordings.playback(reference.frameId(),false).orElseThrow(SessionMediaInputs::unavailable);
        if(!actual.manifestSha256().equals(descriptor.get("recording_manifest_sha256")))throw changed();
    }
    private static void verifyLink(Map<String,Object> row,FrameRecordings.Reference reference){
        if(!reference.recording().sha256().equals(row.get("recording_manifest_sha256"))||!reference.capture().get("payload_sha256").equals(row.get("payload_sha256"))||!reference.packetSha256().equals(row.get("packet_sha256"))
            ||reference.sourcePtsUs()!=((Number)row.get("source_pts_us")).longValue()||reference.seekOffsetMs()!=((Number)row.get("seek_offset_ms")).longValue())throw changed();
    }
    public static boolean same(Map<String,Object> a,Map<String,Object> b){return CanonicalJson.encode(a,16384).sha256().equals(CanonicalJson.encode(b,16384).sha256());}
    public static long endMs(Map<String,Object> descriptor){return ((Number)descriptor.get("duration_us")).longValue()/1000;}
    public static String inputSha(Map<String,Object> descriptor){
        validateDescriptor(descriptor);var value=new LinkedHashMap<String,Object>();value.putAll(Map.of("input_kind","session","profile_version",PROFILE,"source_id",descriptor.get("source_id"),"camera_id",descriptor.get("camera_id"),"source_fingerprint",descriptor.get("source_fingerprint"),"start_ms",0,"end_ms",endMs(descriptor),"source",Map.of("bytes",descriptor.get("source_bytes"),"sha256",descriptor.get("source_sha256")),"session_input",descriptor));return CanonicalJson.encode(value,16384).sha256();
    }
    public static void validateDescriptor(Map<String,Object> value){
        try{
            if(value==null||!value.keySet().equals(Set.of("version","source_id","camera_id","source_fingerprint","namespace","segment_id","recording_manifest_sha256","source_sha256","source_bytes","duration_us","task_id","task_generation","scene_run_id","media_epoch","base_pts_us"))||!VERSION.equals(value.get("version")))throw changed();
            for(String key:List.of("source_id","segment_id","task_id","scene_run_id")){String id=(String)value.get(key);if(!UUID.fromString(id).toString().equals(id))throw changed();}
            if(!((String)value.get("camera_id")).matches("[A-Za-z0-9_-]{1,100}")||!((String)value.get("namespace")).matches("[a-f0-9]{32}"))throw changed();
            for(String key:List.of("source_fingerprint","recording_manifest_sha256","source_sha256"))if(!((String)value.get(key)).matches("[a-f0-9]{64}"))throw changed();
            number(value,"source_bytes",1,16*1024*1024);long duration=number(value,"duration_us",2000000,20000000);
            number(value,"task_generation",1,Long.MAX_VALUE);number(value,"media_epoch",0,Long.MAX_VALUE);Math.addExact(number(value,"base_pts_us",Long.MIN_VALUE,Long.MAX_VALUE),duration);
        }catch(InputRejected error){throw error;}catch(RuntimeException error){throw changed();}
    }
    private static long number(Map<String,Object> value,String key,long low,long high){Object raw=value.get(key);if(!(raw instanceof Long||raw instanceof Integer))throw changed();long n=((Number)raw).longValue();if(n<low||n>high)throw changed();return n;}
    private static InputRejected changed(){return new InputRejected(409,"media_session_changed");}
    private static InputRejected unavailable(){return new InputRejected(404,"media_session_unavailable");}
}
