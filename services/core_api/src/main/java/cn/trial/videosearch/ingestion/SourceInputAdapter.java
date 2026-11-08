package cn.trial.videosearch.ingestion;
import java.util.Map;
public interface SourceInputAdapter {
    String kind();
    String type();
    void validate(String camera,String reference);
    Map<String,Object> snapshot(Map<String,Object> source);
}
