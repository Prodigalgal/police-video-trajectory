package cn.trial.videosearch.ingestion;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SimulatorInputAdapter implements SourceInputAdapter {
    public String kind(){return "simulator-v1";}
    public String type(){return "rtsp";}
    public void validate(String camera,String reference){if(!camera.matches("recording-0[1-4]")||!reference.equals("simulator:"+camera))throw new IllegalArgumentException("Stream adapter does not support source");}
    public Map<String,Object> snapshot(Map<String,Object> source){validate((String)source.get("camera_id"),(String)source.get("source_ref"));var result=new LinkedHashMap<>(source);result.put("adapter",kind());return result;}
}
