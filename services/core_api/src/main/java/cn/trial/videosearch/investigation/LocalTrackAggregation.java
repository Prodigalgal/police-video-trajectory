package cn.trial.videosearch.investigation;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LocalTrackAggregation implements AggregationStrategy {
    public String id(){return "local-track-segments-v1";}
    public List<Map<String,Object>> aggregate(ObservationPoint anchor,List<ObservationPoint> observations,int gap){
        var groups=new TreeMap<String,List<ObservationPoint>>();
        for(var point:observations){
            String key=point.localClock()&&point.trackId()!=null?point.localScope()+":"+point.trackId()+":"+point.className():point.observationId().toString();
            groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(point);
        }
        var segments=new ArrayList<Map<String,Object>>();
        for(var group:groups.values()){
            group.sort(Comparator.comparing((ObservationPoint p)->p.mediaMs()==null?Long.MAX_VALUE:p.mediaMs()).thenComparing(p->p.observationId().toString()));
            var current=new ArrayList<ObservationPoint>();
            for(var point:group){
                if(!current.isEmpty()){
                    var previous=current.getLast();
                    if(point.mediaMs()==null||previous.mediaMs()==null||point.mediaMs()<=previous.mediaMs()||point.mediaMs()-previous.mediaMs()>gap){segments.add(segment(anchor,current));current=new ArrayList<>();}
                }
                current.add(point);
            }
            if(!current.isEmpty())segments.add(segment(anchor,current));
        }
        segments.sort(Comparator.<Map<String,Object>,Boolean>comparing(segment->!Boolean.TRUE.equals(segment.get("contains_anchor"))).thenComparing(segment->(String)segment.get("segment_id")));
        return segments;
    }
    private Map<String,Object> segment(ObservationPoint anchor,List<ObservationPoint> points){
        var first=points.getFirst();var last=points.getLast();boolean clock=first.localClock()&&last.localClock();
        var actions=new ArrayList<Map<String,Object>>();var repeated=new TreeMap<String,List<String>>();
        for(var point:points){Object raw=point.attributes().get("visible_action");if(raw instanceof String text&&!text.isBlank())repeated.computeIfAbsent(text,ignored->new ArrayList<>()).add(point.observationId().toString());}
        repeated.forEach((action,ids)->{if(ids.size()>=2)actions.add(Map.of("action",action,"observations",ids,"kind","repeated_frame_description","temporal_action_verified",false));});
        var value=new LinkedHashMap<String,Object>();
        value.put("segment_id",CanonicalJson.encode(points.stream().map(p->Map.of("id",p.observationId().toString(),"version",p.version())).toList(),65536).sha256());
        value.put("algorithm",id());value.put("contains_anchor",points.stream().anyMatch(point->point.observationId().equals(anchor.observationId())));
        value.put("camera_id",first.cameraId());value.put("camera_name",first.cameraName());value.put("target_type",first.targetType());
        value.put("start_ms",clock?first.mediaMs():null);value.put("end_ms",clock?last.mediaMs():null);value.put("span_ms",clock?last.mediaMs()-first.mediaMs():null);
        value.put("association_kind",points.size()>1?"local_geometry_candidate":"single_observation");value.put("identity_verified",false);
        value.put("observed_frames",points.size());value.put("actions",actions);value.put("observations",points.stream().map(ObservationPoint::view).toList());
        return value;
    }
}
