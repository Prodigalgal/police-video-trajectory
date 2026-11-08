package cn.trial.videosearch.ingestion;
import cn.trial.videosearch.evidence.MediaClipFiles;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ArchiveInputAdapter implements SourceInputAdapter {
    private final MediaClipFiles files;
    public ArchiveInputAdapter(MediaClipFiles files){this.files=files;}
    public String kind(){return "archive-v1";}
    public String type(){return "archive";}
    public void validate(String camera,String reference){if(!camera.matches("recording-0[1-4]")||!reference.equals("archive:"+camera))throw new IllegalArgumentException("Archive adapter does not support source");}
    public Map<String,Object> snapshot(Map<String,Object> source){
        String camera=(String)source.get("camera_id");validate(camera,(String)source.get("source_ref"));
        var result=new LinkedHashMap<>(source);result.put("file",files.inspectRecording(camera));result.put("adapter",kind());return result;
    }
}
