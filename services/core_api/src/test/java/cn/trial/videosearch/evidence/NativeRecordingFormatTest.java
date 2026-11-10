package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Nonvisual manifest/byte-integrity contracts, not video decoding or scene labels. */
class NativeRecordingFormatTest {
    @TempDir Path directory;
    private final UUID identity=UUID.randomUUID();
    private final String worker="recording-contract",namespace=CanonicalJson.sha256(worker).substring(0,32);
    private Map<String,Object> manifest(boolean context,int seconds){
        var value=new LinkedHashMap<String,Object>();
        value.putAll(Map.of("schema_version",context?"same-session-recording-v2":"same-session-recording-v1","segment_id",identity.toString(),"namespace",namespace,
            "task_id",UUID.randomUUID().toString(),"task_generation",1,"worker_id",worker,"source_ref_sha256","a".repeat(64),"camera_id","camera-contract","scene_run_id",UUID.randomUUID().toString()));
        value.putAll(Map.of("media_epoch",0,"origin_pts_us",0,"playback_base_pts_us",0,"time_base_num",1,"time_base_den",90000,"codec","h264","width",640,"height",480,"duration_us",seconds*1_000_000L));
        var packets=new ArrayList<Map<String,Object>>();
        for(int second=0;second<seconds;second++)packets.add(Map.of("pts_us",second*1_000_000L,"dts_us",second*1_000_000L,"sha256","b".repeat(64)));
        value.put("packet_refs",packets);value.put("video",Map.of("bytes",13,"sha256",CanonicalJson.sha256("byte-contract")));
        value.put("quality",Map.of("first_packet_keyframe",true,"packet_sequence_complete",true,"source_clock","same_reader_session","absolute_time_verified",false,"reencoded",false,"inference_invoked",false));
        if(context)value.put("context_window",Map.of("policy","overlap-keyframe-v1","target_seconds",10));
        return value;
    }
    private StreamRecordingFiles.Recording read(Map<String,Object> value)throws Exception{
        Path folder=directory.resolve(namespace).resolve(identity.toString().replace("-",""));Files.createDirectories(folder);
        Files.writeString(folder.resolve("owner.json"),CanonicalJson.encode(Map.of("schema","live-segment-owner-v1","segment_id",identity.toString()),200).payload());
        Files.writeString(folder.resolve("source.mp4"),"byte-contract");
        var encoded=CanonicalJson.encode(value,1024*1024);Files.writeString(folder.resolve("manifest.json"),encoded.payload());
        return new StreamRecordingFiles(directory.toString()).read(namespace,identity,encoded.sha256());
    }
    @Test void contextAndLegacyAreExplicitAndBothRetainPacketProofs()throws Exception{
        assertEquals(5_000_000L,read(manifest(false,5)).durationUs());
        var context=read(manifest(true,40));assertEquals(40_000_000L,context.durationUs());assertEquals(40,context.packets().size());
        assertEquals("session-segment-input-v2",NativeRecordingFormat.manifest(context.manifest().get("schema_version")).inputVersion);
    }
    @Test void legacyDoesNotSilentlyAcquireLongWindowOrNewMetadata(){
        assertThrows(InputRejected.class,()->read(manifest(false,40)));
        var wrong=manifest(false,5);wrong.put("context_window",Map.of("policy","overlap-keyframe-v1","target_seconds",10));
        assertThrows(InputRejected.class,()->read(wrong));
    }
    @Test void invalidContextOrOversizedClockCannotBecomePlayable(){
        var wrong=manifest(true,40);wrong.put("context_window",Map.of("policy","overlap-keyframe-v1","target_seconds",true));
        assertThrows(InputRejected.class,()->read(wrong));
        wrong.put("context_window",Map.of("policy","unverified","target_seconds",10));assertThrows(InputRejected.class,()->read(wrong));
        var duration=manifest(true,40);duration.put("duration_us",75_000_001L);assertThrows(InputRejected.class,()->read(duration));
        var packets=manifest(true,40);packets.put("packet_refs",List.of(Map.of("pts_us",0,"dts_us",0,"sha256","b".repeat(64)),Map.of("pts_us",0,"dts_us",1,"sha256","b".repeat(64))));
        assertThrows(InputRejected.class,()->read(packets));
    }
    @Test void longSessionInputRequiresV2AndStillRejectsOverflow(){
        var value=new LinkedHashMap<String,Object>();value.putAll(Map.of("version","session-segment-input-v2","source_id",UUID.randomUUID().toString(),"camera_id","camera-contract","source_fingerprint","a".repeat(64),"namespace",namespace,"segment_id",identity.toString(),"recording_manifest_sha256","b".repeat(64),"source_sha256","c".repeat(64),"source_bytes",40*1024*1024));
        value.putAll(Map.of("duration_us",40_000_000L,"task_id",UUID.randomUUID().toString(),"task_generation",1,"scene_run_id",UUID.randomUUID().toString(),"media_epoch",0,"base_pts_us",1));
        assertDoesNotThrow(()->SessionMediaInputs.validateDescriptor(value));
        value.put("version","session-segment-input-v1");assertThrows(InputRejected.class,()->SessionMediaInputs.validateDescriptor(value));
        value.put("version","session-segment-input-v2");value.put("base_pts_us",Long.MAX_VALUE-1);assertThrows(InputRejected.class,()->SessionMediaInputs.validateDescriptor(value));
    }
}
