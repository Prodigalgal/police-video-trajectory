package cn.trial.videosearch.investigation;

import java.time.Instant;
import java.util.*;

/** Versioned semantic fact and its exact local tracking/clock scope. No identity assertion. */
public record ObservationPoint(UUID observationId,long version,UUID frameId,String cameraId,String cameraName,String targetType,
    String description,Map<String,Object> attributes,UUID taskId,UUID runId,Long epoch,Long mediaMs,Instant sourceTime,
    UUID trackId,UUID targetId,String className,String evidenceSha,Double longitude,Double latitude){
    public boolean localClock(){return runId!=null&&epoch!=null&&mediaMs!=null;}
    public String localScope(){return cameraId+":"+taskId+":"+runId+":"+epoch;}
    public Map<String,Object> view(){
        var value=new LinkedHashMap<String,Object>();
        value.put("observation_id",observationId.toString());value.put("record_version",version);value.put("frame_id",frameId.toString());
        value.put("camera_id",cameraId);value.put("camera_name",cameraName);value.put("target_type",targetType);value.put("description",description);
        value.put("attributes",attributes);value.put("source_time",sourceTime==null?null:sourceTime.toString());value.put("relative_media_ms",mediaMs);
        value.put("track_id",trackId==null?null:trackId.toString());value.put("evidence_sha256",evidenceSha);
        value.put("location",longitude==null||latitude==null?null:Map.of("longitude",longitude,"latitude",latitude,"coordinate_system","GCJ-02"));
        return value;
    }
}
