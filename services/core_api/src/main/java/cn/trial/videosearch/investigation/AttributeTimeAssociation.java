package cn.trial.videosearch.investigation;

import cn.trial.videosearch.attributes.ColorTerms;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

@Component
public class AttributeTimeAssociation implements AssociationStrategy {
    public String id(){return "attribute-time-candidates-v1";}
    public Optional<Map<String,Object>> suggest(ObservationPoint anchor,ObservationPoint candidate){
        if(anchor.sourceTime()==null||candidate.sourceTime()==null||anchor.cameraId().equals(candidate.cameraId())||!anchor.targetType().equals(candidate.targetType()))return Optional.empty();
        var reasons=new ArrayList<String>();var conflicts=new ArrayList<String>();int matches=0;
        for(String key:List.of("upper_color","lower_color","vehicle_color","transport_type")){
            Object left=anchor.attributes().get(key),right=candidate.attributes().get(key);
            if(left instanceof String a&&right instanceof String b&&!a.isBlank()&&!b.isBlank()){
                boolean same=key.endsWith("color")?Objects.equals(ColorTerms.canonical(a),ColorTerms.canonical(b)):a.equalsIgnoreCase(b);
                if(same){reasons.add(key+"_matches");matches++;}else conflicts.add(key+"_differs");
            }
        }
        if(matches==0||!conflicts.isEmpty())return Optional.empty();
        long delta=Duration.between(anchor.sourceTime(),candidate.sourceTime()).getSeconds();Double distance=null;
        if(anchor.longitude()!=null&&candidate.longitude()!=null){
            distance=distance(anchor.latitude(),anchor.longitude(),candidate.latitude(),candidate.longitude());
            double maximumSpeed="person".equals(anchor.targetType())?10:60;
            if(distance>Math.max(200,Math.abs(delta)*maximumSpeed))return Optional.empty();
            reasons.add("straight_line_time_bound_passed");
        }
        var value=new LinkedHashMap<String,Object>();value.put("observation",candidate.view());value.put("algorithm",id());
        value.put("reasons",reasons);value.put("conflicts",conflicts);value.put("matched_attributes",matches);value.put("delta_seconds",delta);value.put("distance_meters",distance==null?null:Math.round(distance));
        value.put("rank_value",matches*100-Math.min(Math.abs(delta),3600)/60.0);value.put("score_calibrated",false);value.put("identity_verified",false);value.put("review_state","pending");
        return Optional.of(value);
    }
    private static double distance(double lat1,double lon1,double lat2,double lon2){double a=Math.pow(Math.sin(Math.toRadians(lat2-lat1)/2),2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.pow(Math.sin(Math.toRadians(lon2-lon1)/2),2);return 6371000*2*Math.asin(Math.min(1,Math.sqrt(a)));}
}
