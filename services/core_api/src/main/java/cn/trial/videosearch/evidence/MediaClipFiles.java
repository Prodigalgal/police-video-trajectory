package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Fixed names under a separate media root. No caller-provided file paths. */
@Service
public class MediaClipFiles {
    public static final String SESSION_SCHEMA="recorded-session-clip-v1";
    public enum Mode {MANIFEST,VIDEO,SAMPLE}
    public static final long MAX_BYTES=64L*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path root;
    private final Path recordings;
    public MediaClipFiles(@Value("${VIDEO_MEDIA_CLIP_DIR:/media-clips}")String root,@Value("${VIDEO_ARCHIVE_DIR:/recordings}")String recordings){this.root=Path.of(root).toAbsolutePath().normalize();this.recordings=Path.of(recordings).toAbsolutePath().normalize();}
    public record Assets(Map<String,Object> manifest,String payload,String sha256,UUID sourceId,String camera,String sourceSha256,long sourceBytes,Path video,long videoBytes,EvidenceFiles.FrameFile sample){}
    @SuppressWarnings("unchecked")
    public Assets verify(UUID id,String expected,Mode mode,int sampleIndex){
        try{
            Path folder=root.resolve(id.toString().replace("-",""));
            if(Files.isSymbolicLink(root)||Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw rejected("media_unavailable");
            byte[] raw=read(folder.resolve("manifest.json"),64*1024);var manifest=JSON.readValue(raw,Map.class);
            boolean session=session(manifest);
            var names=new ArrayList<>(List.of("schema_version","clip_id","source_id","camera_id","source","requested_start_ms","requested_end_ms","sample_period_ms","quality","video","samples","actual_start_us","actual_end_us","decoded_frames","max_decoded_gap_us","producer_version"));if(session)names.add("session_input");keys(manifest,names.toArray(String[]::new));text(manifest,"producer_version");
            var encoded=CanonicalJson.encode(manifest,64*1024);
            if(!encoded.sha256().equals(expected)||!(session||"recorded-media-clip-v1".equals(manifest.get("schema_version")))||!id.toString().equals(manifest.get("clip_id")))throw rejected("media_manifest_changed");
            String camera=text(manifest,"camera_id");if(!camera.matches(session?"[A-Za-z0-9_-]{1,100}":"recording-0[1-4]"))throw rejected("media_manifest_changed");
            UUID source=UUID.fromString(text(manifest,"source_id"));Map<String,Object> original=object(manifest,"source"),video=object(manifest,"video"),quality=object(manifest,"quality");
            keys(original,"bytes","sha256");keys(video,"bytes","sha256","width","height","codec","frame_offsets_ms");
            String sourceSha=digest(original);long sourceBytes=number(original,"bytes",1,8L*1024*1024*1024);
            long maxWindowMs=session?NativeRecordingFormat.CONTEXT.maxDurationUs/1000:30000;
            long start=number(manifest,"requested_start_ms",0,Long.MAX_VALUE-maxWindowMs),end=number(manifest,"requested_end_ms",start+2000,start+maxWindowMs);
            if(session){
                var input=object(manifest,"session_input");SessionMediaInputs.validateDescriptor(input);
                if(!source.toString().equals(input.get("source_id"))||!camera.equals(input.get("camera_id"))||!sourceSha.equals(input.get("source_sha256"))||sourceBytes!=((Number)input.get("source_bytes")).longValue()||start!=0||end!=SessionMediaInputs.endMs(input))throw rejected("media_manifest_changed");
            }
            long first=number(manifest,"actual_start_us",Math.multiplyExact(start,1000),Math.addExact(Math.multiplyExact(start,1000),250000));
            long last=number(manifest,"actual_end_us",Math.max(first+1,Math.multiplyExact(end,1000)-250000),Math.multiplyExact(end,1000)-1);
            number(manifest,"decoded_frames",2,18000);number(manifest,"max_decoded_gap_us",1,250000);number(manifest,"sample_period_ms",1000,1000);
            if(!quality.equals(Map.of("window_complete",true,"source_clock",session?"same_session_segment_relative":"recording_relative","absolute_time_verified",false,"identity_association_verified",false,"inference_invoked",false)))throw rejected("media_manifest_changed");
            long width=number(video,"width",2,1280),height=number(video,"height",2,20000),videoBytes=number(video,"bytes",1,MAX_BYTES);String videoSha=digest(video);
            if(width%2!=0||height%2!=0||width*height>50000000||!"h264".equals(video.get("codec")))throw rejected("media_manifest_changed");
            List<?> positions=list(video,"frame_offsets_ms",2,(int)(maxWindowMs/100+1));long previous=-1;
            for(Object value:positions){long offset=integer(value,0,(last-first)/1000+1);if(offset<=previous||previous<0&&offset!=0||previous>=0&&offset-previous>350)throw rejected("media_manifest_changed");previous=offset;}
            if((last-first)/1000-previous>350)throw rejected("media_manifest_changed");
            List<?> samples=list(manifest,"samples",2,(int)(maxWindowMs/1000+1));
            if(mode==Mode.SAMPLE&&(sampleIndex<0||sampleIndex>=samples.size()))throw new InputRejected(404,"media_sample_unavailable");
            long total=videoBytes+raw.length;long previousPts=-1;EvidenceFiles.FrameFile selected=null;
            for(int index=0;index<samples.size();index++){
                if(!(samples.get(index) instanceof Map<?,?> candidate))throw rejected("media_manifest_changed");
                var sample=(Map<String,Object>)candidate;number(sample,"index",index,index);
                keys(sample,"index","source_pts_us","offset_ms","bytes","sha256");
                long pts=number(sample,"source_pts_us",first,last),offset=number(sample,"offset_ms",0,(last-first)/1000+1),bytes=number(sample,"bytes",1,8*1024*1024);String sha=digest(sample);
                if(index==0&&pts!=first||index>0&&(pts-previousPts<750000||pts-previousPts>1250000)||Math.abs(pts-first-offset*1000)>500)throw rejected("media_manifest_changed");
                previousPts=pts;total+=bytes;
                if(mode==Mode.MANIFEST||mode==Mode.SAMPLE&&index==sampleIndex){
                    byte[] image=read(folder.resolve("sample-%03d.jpg".formatted(index)),8*1024*1024);
                    if(image.length!=bytes||!sha.equals(hash(image)))throw rejected("media_evidence_changed");
                    try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(image))){
                        var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw rejected("media_evidence_changed");var reader=readers.next();
                        try{reader.setInput(input,true,true);if(reader.getWidth(0)!=width||reader.getHeight(0)!=height||!reader.getFormatName().equalsIgnoreCase("JPEG"))throw rejected("media_evidence_changed");}
                        finally{reader.dispose();}
                    }
                    if(index==sampleIndex)selected=new EvidenceFiles.FrameFile(image,(int)width,(int)height,sha);
                }
            }
            if((last-previousPts)>1250000||total>MAX_BYTES)throw rejected("media_manifest_changed");
            Path path=folder.resolve("clip.mp4");
            if(mode!=Mode.SAMPLE){var actual=fileDigest(path,MAX_BYTES);if(actual.bytes()!=videoBytes||!actual.sha256().equals(videoSha))throw rejected("media_evidence_changed");}
            return new Assets(manifest,encoded.payload(),encoded.sha256(),source,camera,sourceSha,sourceBytes,path,videoBytes,selected);
        }catch(InputRejected error){throw error;}
        catch(IOException error){throw new InputRejected(422,"media_evidence_unavailable");}
        catch(RuntimeException error){throw rejected("media_manifest_changed");}
    }
    public static boolean session(Map<String,Object> manifest){return SESSION_SCHEMA.equals(manifest.get("schema_version"));}
    public void verifyRecording(Assets assets){
        try{
            if(Files.isSymbolicLink(recordings))throw rejected("media_source_changed");
            var original=fileDigest(recordings.resolve(assets.camera()+".mp4"),8L*1024*1024*1024);
            if(original.bytes()!=assets.sourceBytes()||!original.sha256().equals(assets.sourceSha256()))throw rejected("media_source_changed");
        }catch(IOException error){throw new InputRejected(422,"media_source_unavailable");}
    }
    public Map<String,Object> inspectRecording(String camera){
        if(camera==null||!camera.matches("recording-0[1-4]"))throw new IllegalArgumentException("Invalid archive camera");
        try{
            if(Files.isSymbolicLink(recordings))throw new IOException("unsafe archive root");
            var value=fileDigest(recordings.resolve(camera+".mp4"),8L*1024*1024*1024);
            if(value.bytes()<1)throw new IOException("empty archive");
            return Map.of("bytes",value.bytes(),"sha256",value.sha256());
        }catch(IOException error){throw new InputRejected(422,"media_source_unavailable");}
    }
    private record FileDigest(long bytes,String sha256){}
    private static FileDigest fileDigest(Path path,long limit)throws IOException{
        var digest=sha256();long bytes=0;
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var input=java.nio.channels.Channels.newInputStream(channel)){
            if(channel.size()>limit||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("media size unavailable");
            byte[] buffer=new byte[1024*1024];int length;
            while((length=input.read(buffer))!=-1){bytes+=length;if(bytes>limit)throw new IOException("media size exceeded");digest.update(buffer,0,length);}
        }
        return new FileDigest(bytes,HexFormat.of().formatHex(digest.digest()));
    }
    private static byte[] read(Path path,int limit)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("media file unavailable");
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var input=java.nio.channels.Channels.newInputStream(channel)){
            byte[] value=input.readNBytes(limit+1);if(value.length==0||value.length>limit)throw new IOException("media size exceeded");return value;
        }
    }
    private static String hash(byte[] value){return HexFormat.of().formatHex(sha256().digest(value));}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static InputRejected rejected(String code){return new InputRejected(409,code);}
    private static String text(Map<String,Object> map,String key){if(!(map.get(key) instanceof String text)||text.length()>200)throw rejected("media_manifest_changed");return text;}
    private static void keys(Map<String,Object> map,String...allowed){if(!map.keySet().equals(Set.of(allowed)))throw rejected("media_manifest_changed");}
    private static String digest(Map<String,Object> map){String value=text(map,"sha256");if(!value.matches("[a-f0-9]{64}"))throw rejected("media_manifest_changed");return value;}
    private static long integer(Object value,long min,long max){
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw rejected("media_manifest_changed");return ((Number)value).longValue();
    }
    private static long number(Map<String,Object> map,String key,long min,long max){return integer(map.get(key),min,max);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> map,String key){if(!(map.get(key) instanceof Map<?,?> value))throw rejected("media_manifest_changed");return (Map<String,Object>)value;}
    private static List<?> list(Map<String,Object> map,String key,int min,int max){if(!(map.get(key) instanceof List<?> value)||value.size()<min||value.size()>max)throw rejected("media_manifest_changed");return value;}
}
