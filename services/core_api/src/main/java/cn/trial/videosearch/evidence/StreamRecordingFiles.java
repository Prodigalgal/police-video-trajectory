package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class StreamRecordingFiles {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path root;
    private final RecordingContentVerifier content=new RecordingContentVerifier();
    public StreamRecordingFiles(@Value("${VIDEO_LIVE_RECORDING_DIR:/recordings-live}")String root){this.root=Path.of(root).toAbsolutePath().normalize();}
    public record Recording(Map<String,Object> manifest,String payload,String sha256,Path path,long bytes,long durationUs,long basePtsUs,Map<Long,String> packets){}
    public Recording read(String namespace,UUID id,String expected){return verify(namespace,id,expected,null);}
    public Recording describe(String namespace,UUID id,String expected,Map<String,Object> stored){return verify(namespace,id,expected,Objects.requireNonNull(stored));}
    @SuppressWarnings("unchecked")
    private Recording verify(String namespace,UUID id,String expected,Map<String,Object> stored){
        if(namespace==null||!namespace.matches("[a-f0-9]{32}")||id==null||expected==null||!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid recording reference");
        try{
            Path owner=root.resolve(namespace),folder=owner.resolve(id.toString().replace("-",""));
            Map<String,Object> value=stored;
            if(stored==null){
                if(Files.isSymbolicLink(root)||Files.isSymbolicLink(owner)||Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new InputRejected(422,"recording_file_unavailable");
                var marker=JSON.readValue(bytes(folder.resolve("owner.json"),200),Map.class);
                if(!marker.equals(Map.of("schema","live-segment-owner-v1","segment_id",id.toString())))throw invalid();
                value=JSON.readValue(bytes(folder.resolve("manifest.json"),NativeRecordingFormat.CONTEXT.maxManifestBytes),Map.class);
            }
            var format=NativeRecordingFormat.manifest(value.get("schema_version"));
            var names=new ArrayList<>(List.of("schema_version","segment_id","namespace","task_id","task_generation","worker_id","source_ref_sha256","camera_id","scene_run_id","media_epoch","origin_pts_us","playback_base_pts_us","time_base_num","time_base_den","codec","width","height","duration_us","packet_refs","video","quality"));
            if(format==NativeRecordingFormat.CONTEXT){
                names.add("context_window");var context=object(value,"context_window");keys(context,"policy","target_seconds");
                if(!"overlap-keyframe-v1".equals(context.get("policy"))||!Set.of(3L,5L,10L).contains(number(context,"target_seconds",3,10)))throw invalid();
            }
            keys(value,names.toArray(String[]::new));
            var encoded=CanonicalJson.encode(value,format.maxManifestBytes);
            if(!encoded.sha256().equals(expected)||!id.toString().equals(value.get("segment_id"))||!namespace.equals(value.get("namespace")))throw invalid();
            String worker=text(value,"worker_id");if(!namespace.equals(CanonicalJson.sha256(worker).substring(0,32))||!worker.matches("[A-Za-z0-9_.-]{1,100}")||!text(value,"camera_id").matches("[A-Za-z0-9_-]{1,100}"))throw invalid();
            UUID.fromString(text(value,"task_id"));UUID.fromString(text(value,"scene_run_id"));digest(value,"source_ref_sha256");
            number(value,"task_generation",1,Long.MAX_VALUE);number(value,"media_epoch",0,Long.MAX_VALUE);number(value,"time_base_num",1,1000000000);number(value,"time_base_den",1,1000000000);
            long width=number(value,"width",1,20000),height=number(value,"height",1,20000);if(width*height>50000000||!"h264".equals(value.get("codec")))throw invalid();
            long base=number(value,"playback_base_pts_us",Long.MIN_VALUE,Long.MAX_VALUE),duration=number(value,"duration_us",1,format.maxDurationUs);number(value,"origin_pts_us",Long.MIN_VALUE,Long.MAX_VALUE);
            var quality=object(value,"quality");if(!quality.equals(Map.of("first_packet_keyframe",true,"packet_sequence_complete",true,"source_clock","same_reader_session","absolute_time_verified",false,"reencoded",false,"inference_invoked",false)))throw invalid();
            if(!(value.get("packet_refs") instanceof List<?> rows)||rows.isEmpty()||rows.size()>format.maxPackets)throw invalid();
            var packets=new LinkedHashMap<Long,String>();Long previous=null;
            for(Object raw:rows){
                if(!(raw instanceof Map<?,?> row))throw invalid();var packet=(Map<String,Object>)row;keys(packet,"pts_us","dts_us","sha256");
                long pts=number(packet,"pts_us",Long.MIN_VALUE,Long.MAX_VALUE),dts=number(packet,"dts_us",Long.MIN_VALUE,Long.MAX_VALUE),offset=Math.subtractExact(pts,base);
                if(offset<0||offset>=duration+2||previous!=null&&(Math.subtractExact(dts,previous)<=0||Math.subtractExact(dts,previous)>2000000)||packets.put(pts,digest(packet,"sha256"))!=null)throw invalid();previous=dts;
            }
            if(packets.keySet().iterator().next()!=base)throw invalid();
            var video=object(value,"video");keys(video,"bytes","sha256");long size=number(video,"bytes",1,format.maxBytes);String expectedVideo=digest(video,"sha256");
            Path path=folder.resolve("source.mp4");
            if(stored!=null)return new Recording(value,encoded.payload(),encoded.sha256(),path,size,duration,base,Map.copyOf(packets));
            content.verify(path,size,expectedVideo);
            return new Recording(value,encoded.payload(),encoded.sha256(),path,size,duration,base,Map.copyOf(packets));
        }catch(InputRejected error){throw error;}
        catch(IOException error){throw new InputRejected(422,"recording_file_unavailable");}
        catch(RuntimeException error){throw invalid();}
    }
    private static byte[] bytes(Path path,int limit)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("recording metadata missing");
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var input=java.nio.channels.Channels.newInputStream(channel)){
            byte[] raw=input.readNBytes(limit+1);if(raw.length>limit)throw new IOException("recording metadata exceeded");return raw;
        }
    }
    private static InputRejected invalid(){return new InputRejected(409,"recording_manifest_changed");}
    private static void keys(Map<String,Object> value,String...keys){if(!value.keySet().equals(Set.of(keys)))throw invalid();}
    private static String text(Map<String,Object> value,String key){if(!(value.get(key) instanceof String text)||text.length()>200)throw invalid();return text;}
    private static String digest(Map<String,Object> value,String key){String text=text(value,key);if(!text.matches("[a-f0-9]{64}"))throw invalid();return text;}
    static long number(Map<String,Object> value,String key,long low,long high){if(!(value.get(key) instanceof Integer||value.get(key) instanceof Long))throw invalid();long n=((Number)value.get(key)).longValue();if(n<low||n>high)throw invalid();return n;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> value,String key){if(!(value.get(key) instanceof Map<?,?> map))throw invalid();return (Map<String,Object>)map;}
}
