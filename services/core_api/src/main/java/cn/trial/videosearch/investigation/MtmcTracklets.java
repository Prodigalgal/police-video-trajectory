package cn.trial.videosearch.investigation;

import cn.trial.videosearch.attributes.ColorTerms;
import cn.trial.videosearch.evidence.AppearanceIndex;
import cn.trial.videosearch.support.CanonicalJson;
import java.time.*;
import java.util.*;

/** Bounded observed tracklets. Unknown intervals, geography and identities remain unknown. */
final class MtmcTracklets {
    static final int MAX_GAP_MS=5500,MAX_SIGHTINGS=50,MAX_SPAN_MS=300000;
    record Shot(AppearanceIndex.Ranked hit,ObservationPoint observation,Map<String,Object> plate){}
    record Segment(List<Shot> shots){
        double appearance(){return shots.stream().mapToDouble(shot->shot.hit().score()).boxed().sorted(Comparator.reverseOrder()).limit(3).mapToDouble(Double::doubleValue).average().orElse(-1);}
        Shot representative(){return shots.stream().max(Comparator.comparingDouble((Shot shot)->shot.hit().score()).thenComparing(shot->shot.observation().observationId().toString(),Comparator.reverseOrder())).orElseThrow();}
    }
    static List<Segment> group(List<Shot> input){
        var grouped=new TreeMap<String,List<Shot>>();
        for(var shot:input){var point=shot.observation();var c=shot.hit().clock();
            boolean usable=point.localClock()&&point.trackId()!=null;
            String key=usable?point.localScope()+":"+point.trackId()+":"+point.className():"single:"+point.observationId();
            grouped.computeIfAbsent(key,ignored->new ArrayList<>()).add(shot);
        }
        var output=new ArrayList<Segment>();
        for(var shots:grouped.values()){
            shots.sort(Comparator.comparingLong((Shot shot)->position(shot)).thenComparing(shot->shot.observation().observationId().toString()));
            var current=new ArrayList<Shot>();long previous=-1,first=-1;
            for(var shot:shots){long position=position(shot);
                if(!current.isEmpty()&&(position<=previous||position-previous>MAX_GAP_MS||position-first>MAX_SPAN_MS||current.size()>=MAX_SIGHTINGS)){
                    output.add(new Segment(List.copyOf(current)));current.clear();
                }
                if(current.isEmpty())first=position;current.add(shot);previous=position;
            }
            if(!current.isEmpty())output.add(new Segment(List.copyOf(current)));
        }
        return List.copyOf(output);
    }
    private static long position(Shot shot){return shot.observation().mediaMs()==null?0:shot.observation().mediaMs();}
    private static Instant firstTime(Segment segment){return segment.shots().stream().map(shot->shot.observation().sourceTime()).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);}
    private static Instant lastTime(Segment segment){return segment.shots().stream().map(shot->shot.observation().sourceTime()).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);}
    private static boolean verifiedClock(Segment segment){return segment.shots().stream().allMatch(shot->shot.observation().sourceTime()!=null);}
    static String temporal(Segment anchor,Segment candidate){
        if(!verifiedClock(anchor)||!verifiedClock(candidate))return "unknown";
        if(firstTime(candidate).isAfter(lastTime(anchor)))return "after";
        if(lastTime(candidate).isBefore(firstTime(anchor)))return "before";
        return "overlap";
    }
    static Double delta(Segment anchor,Segment candidate){
        return "unknown".equals(temporal(anchor,candidate))?null:Duration.between(firstTime(anchor),firstTime(candidate)).toMillis()/1000.;
    }
    static boolean withinWindow(Segment anchor,Segment candidate,int seconds){
        if("unknown".equals(temporal(anchor,candidate)))return true;
        return !firstTime(candidate).isAfter(lastTime(anchor).plusSeconds(seconds))&&!lastTime(candidate).isBefore(firstTime(anchor).minusSeconds(seconds));
    }
    private static Map<String,String> attributes(Segment segment){
        var output=new TreeMap<String,String>();
        for(String key:List.of("upper_color","lower_color","vehicle_color","transport_type")){
            var values=new TreeSet<String>();
            for(var shot:segment.shots()){Object raw=shot.observation().attributes().get(key);if(raw instanceof String value&&!value.isBlank())values.add(key.endsWith("color")?ColorTerms.canonical(value):value.toLowerCase(Locale.ROOT));}
            if(values.size()==1)output.put(key,values.first());
        }
        return output;
    }
    private static Set<String> plates(Segment segment){
        var values=new TreeSet<String>();for(var shot:segment.shots())if("ready".equals(shot.plate().get("state"))&&shot.plate().get("number") instanceof String value)values.add(value);return values;
    }
    static MtmcStrategy.Evidence evidence(Segment anchor,Segment candidate,int seconds){
        var left=attributes(anchor);var right=attributes(candidate);int matches=0,conflicts=0;
        for(var entry:left.entrySet())if(right.containsKey(entry.getKey())){if(entry.getValue().equals(right.get(entry.getKey())))matches++;else conflicts++;}
        var first=plates(anchor);var second=plates(candidate);String relation="unknown";
        if(first.size()>1||second.size()>1)relation="inconsistent_track";
        else if(first.size()==1&&second.size()==1)relation=first.equals(second)?"matches":"conflicts";
        return new MtmcStrategy.Evidence(candidate.appearance(),matches,conflicts,relation,temporal(anchor,candidate),delta(anchor,candidate),seconds);
    }
    static Map<String,Object> freeze(Segment anchor,Segment candidate,MtmcStrategy strategy,int seconds){return freeze(anchor,candidate,strategy,seconds,Map.of());}
    static Map<String,Object> freeze(Segment anchor,Segment candidate,MtmcStrategy strategy,int seconds,Map<String,Object> platformLocator){
        var evidence=evidence(anchor,candidate,seconds);var decision=strategy.evaluate(evidence);var chosen=candidate.representative();
        var sightings=new ArrayList<Map<String,Object>>();
        for(var shot:candidate.shots())sightings.add(Map.of("source",shot.hit().source(),"clock",shot.hit().clock(),"similarity",shot.hit().score(),"observation",shot.observation().view(),"plate",shot.plate()));
        var representative=new LinkedHashMap<>(chosen.observation().view());representative.put("media_offset_ms",chosen.hit().clock().get("media_offset_ms"));
        var period=new LinkedHashMap<String,Object>();
        boolean local=candidate.shots().stream().allMatch(shot->shot.observation().localClock());
        period.put("clock",verifiedClock(candidate)?"verified_source":local?"relative_media":"unknown");
        period.put("start_ms",local?position(candidate.shots().getFirst()):null);period.put("end_ms",local?position(candidate.shots().getLast()):null);
        period.put("start_time",verifiedClock(candidate)?firstTime(candidate).toString():null);period.put("end_time",verifiedClock(candidate)?lastTime(candidate).toString():null);
        var value=new LinkedHashMap<String,Object>();
        value.put("segment_id",CanonicalJson.encode(candidate.shots().stream().map(shot->Map.of("id",shot.observation().observationId().toString(),"version",shot.observation().version())).toList(),65536).sha256());
        value.put("representative",representative);value.put("period",period);value.put("sightings",sightings);value.put("observed_frames",sightings.size());
        value.put("appearance_similarity",candidate.appearance());value.put("rank_score",decision.score());value.put("reasons",decision.reasons());value.put("conflicts",decision.conflicts());
        value.put("temporal_relation",evidence.temporalRelation());value.put("delta_seconds",evidence.deltaSeconds());value.put("plate_relation",evidence.plateRelation());
        value.put("attribute_matches",evidence.attributeMatches());value.put("attribute_conflicts",evidence.attributeConflicts());value.put("identity_verified",false);
        value.put("association_kind","cross_camera_candidate");value.put("geography_state","camera_topology_unverified");
        var locator=new LinkedHashMap<String,Object>();locator.put("task_id",chosen.observation().taskId()==null?null:chosen.observation().taskId().toString());locator.put("scene_run_id",chosen.observation().runId()==null?null:chosen.observation().runId().toString());locator.put("media_epoch",chosen.observation().epoch());
        locator.put("platform_id",platformLocator==null?null:platformLocator.get("platform_id"));locator.put("device_id",platformLocator==null?null:platformLocator.get("device_id"));locator.put("channel_id",platformLocator==null?null:platformLocator.get("channel_id"));
        if(platformLocator!=null&&platformLocator.get("adapter_kind")!=null){locator.put("adapter_kind",platformLocator.get("adapter_kind"));locator.put("clock_verified",Boolean.TRUE.equals(platformLocator.get("clock_verified")));}
        value.put("source_locator",locator);
        return value;
    }
}
