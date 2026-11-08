package cn.trial.videosearch.investigation;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.clues.ClueCollections;
import cn.trial.videosearch.geography.CameraLocations;
import cn.trial.videosearch.evidence.ProcessingCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class InvestigationService {
    private final InvestigationRepository repository;
    private final Map<String,AggregationStrategy> aggregations;
    private final Map<String,AssociationStrategy> associations;
    private final ProcessingCatalog catalog;
    private final CameraLocations locations;
    private final ClueCollections clues;
    private final boolean enabled;
    public InvestigationService(InvestigationRepository repository,List<AggregationStrategy> aggregations,List<AssociationStrategy> associations,ProcessingCatalog catalog,CameraLocations locations,ClueCollections clues,@Value("${VIDEO_INVESTIGATION_ENABLED:false}")boolean enabled){
        this.repository=repository;this.catalog=catalog;this.locations=locations;this.clues=clues;this.enabled=enabled;
        this.aggregations=aggregations.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(AggregationStrategy::id,java.util.function.Function.identity()));
        this.associations=associations.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(AssociationStrategy::id,java.util.function.Function.identity()));
    }
    public void requireEnabled(){if(!enabled)throw new AdminService.Rejected(404,"investigation_disabled");}
    public Map<String,Object> capabilities(){
        var snapshot=catalog.current();
        return Map.of("enabled",enabled,"aggregation_plugins",aggregations.keySet().stream().sorted().toList(),"association_plugins",associations.keySet().stream().sorted().toList(),"modules",snapshot==null?Map.of():snapshot.modules(),"catalog_revision",snapshot==null?0:snapshot.revision(),"map_provider","amap","coordinate_system","GCJ-02","maximum_window_ms",120000,"maximum_observations",200);
    }
    public Map<String,Object> timeline(UUID id,Long expected,int before,int after,int gap){
        requireEnabled();if(before<0||before>120000||after<0||after>120000||gap<1000||gap>30000)throw new IllegalArgumentException("Invalid investigation window");
        var snapshot=catalog.current();var aggregation=aggregations.get(catalog.strategy(snapshot,"temporal_aggregation","local-track-segments-v1",aggregations.keySet()));
        var anchor=repository.anchor(id,expected);var rows=repository.nearby(anchor,before,after,201);boolean truncated=rows.size()>200;
        var selected=new ArrayList<>(rows.subList(0,Math.min(rows.size(),200)));
        if(selected.stream().noneMatch(point->point.observationId().equals(id))){if(selected.size()==200)selected.removeLast();selected.add(anchor);}
        return Map.of("anchor",anchor.view(),"segments",aggregation.aggregate(anchor,selected,gap),"truncated",truncated,"maximum_gap_ms",gap,"clock_available",anchor.localClock(),"continuous_video_verified",false);
    }
    public Map<String,Object> candidates(UUID id,Long expected,int seconds,int limit){
        requireEnabled();if(seconds<1||seconds>3600||limit<1||limit>50)throw new IllegalArgumentException("Invalid candidate window");
        var snapshot=catalog.current();var association=associations.get(catalog.strategy(snapshot,"candidate_association","attribute-time-candidates-v1",associations.keySet()));
        var anchor=repository.anchor(id,expected);
        var rows=repository.otherCameras(anchor,seconds,201);var suggestions=rows.stream().limit(200).map(point->association.suggest(anchor,point)).flatMap(Optional::stream).sorted(Comparator.<Map<String,Object>>comparingDouble(value->-((Number)value.get("rank_value")).doubleValue()).thenComparing(value->String.valueOf(((Map<?,?>)value.get("observation")).get("observation_id")))).toList();
        return Map.of("anchor",anchor.view(),"items",suggestions.subList(0,Math.min(limit,suggestions.size())),"truncated",rows.size()>200||suggestions.size()>limit,"source_time_available",anchor.sourceTime()!=null,"association_kind","candidate_only","algorithm",association.id());
    }
    @SuppressWarnings("unchecked") public Map<String,Object> collectionMap(UUID id){
        requireEnabled();var collection=clues.read(id);var nodes=(List<Map<String,Object>>)collection.get("nodes");var byCamera=new HashMap<String,Map<String,Object>>();
        for(var location:locations.list())byCamera.put((String)location.get("camera_id"),location);
        var points=new ArrayList<Map<String,Object>>();int missing=0;
        for(var node:nodes){if(!Boolean.TRUE.equals(node.get("available")))continue;var location=byCamera.get(node.get("camera_id"));
            if(location==null||location.get("longitude")==null){missing++;continue;}
            points.add(Map.of("observation_id",node.get("observation_id"),"camera_id",node.get("camera_id"),"camera_name",node.get("camera_name"),"longitude",location.get("longitude"),"latitude",location.get("latitude"),"location_revision",location.get("revision")));
        }
        var available=new HashSet<Object>();points.forEach(point->available.add(point.get("observation_id")));
        var links=((List<Map<String,Object>>)collection.get("links")).stream().filter(link->available.contains(link.get("from_observation_id"))&&available.contains(link.get("to_observation_id"))&&!"rejected".equals(link.get("review_state"))&&!Boolean.TRUE.equals(link.get("review_stale"))).toList();
        return Map.of("collection",collection.get("collection"),"points",points,"links",links,"missing_locations",missing,"coordinate_system","GCJ-02","path_kind","candidate_links_not_travel_route");
    }
}
