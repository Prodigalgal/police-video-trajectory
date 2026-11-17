package cn.trial.videosearch.investigation;

import cn.trial.videosearch.evidence.AppearanceIndex;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MtmcTrackletsTest {
    private static final UUID TASK=UUID.randomUUID(),RUN=UUID.randomUUID(),TRACK=UUID.randomUUID();
    private static MtmcTracklets.Shot shot(String camera,long epoch,long position,double score,Instant time,Map<String,Object> attributes,Map<String,Object> plate){
        UUID id=UUID.randomUUID(),frame=UUID.randomUUID();
        var point=new ObservationPoint(id,1,frame,camera,"点位"+camera,"vehicle","白色轿车",attributes,TASK,RUN,epoch,position,time,TRACK,UUID.randomUUID(),"car","a".repeat(64),null,null);
        var clock=new LinkedHashMap<String,Object>();clock.put("task_id",TASK.toString());clock.put("scene_run_id",RUN.toString());clock.put("media_epoch",epoch);clock.put("relative_media_ms",position);clock.put("media_offset_ms",position+1000);clock.put("source_time",time==null?null:time.toString());
        return new MtmcTracklets.Shot(new AppearanceIndex.Ranked(Map.of("observation_id",id.toString(),"camera_id",camera),clock,TRACK.toString(),score),point,plate);
    }
    private static MtmcTracklets.Shot shot(String camera,long epoch,long position,double score){return shot(camera,epoch,position,score,null,Map.of(),Map.of("state","disabled"));}
    @Test void periodsSplitCameraEpochGapDuplicateTimestampAndLongTracks(){
        var points=List.of(shot("a",0,0,.9),shot("a",0,1000,.8),shot("a",0,7000,.7),shot("a",1,1000,.6),shot("b",0,1000,.5));
        var groups=MtmcTracklets.group(points);assertEquals(4,groups.size());assertTrue(groups.stream().anyMatch(group->group.shots().size()==2));
        assertEquals(2,MtmcTracklets.group(List.of(shot("a",0,0,.9),shot("a",0,0,.8))).size());
        var many=new ArrayList<MtmcTracklets.Shot>();for(int i=0;i<51;i++)many.add(shot("a",0,i*1000,.8));
        var bounded=MtmcTracklets.group(many);assertEquals(2,bounded.size());assertTrue(bounded.stream().allMatch(group->group.shots().size()<=50));
    }
    @Test void unknownAbsoluteClockRemainsSearchableWithoutInventedOrder(){
        var anchor=new MtmcTracklets.Segment(List.of(shot("a",0,10000,.9)));
        var candidate=new MtmcTracklets.Segment(List.of(shot("b",0,1000,.8),shot("b",0,2000,.9)));
        assertEquals("unknown",MtmcTracklets.temporal(anchor,candidate));assertNull(MtmcTracklets.delta(anchor,candidate));assertTrue(MtmcTracklets.withinWindow(anchor,candidate,1));
        var item=MtmcTracklets.freeze(anchor,candidate,new EvidenceFusionMtmcStrategy(),600);
        assertEquals(false,item.get("identity_verified"));assertEquals("unknown",item.get("temporal_relation"));
        var period=(Map<?,?>)item.get("period");assertEquals("relative_media",period.get("clock"));assertNull(period.get("start_time"));assertEquals(1000L,period.get("start_ms"));
        assertEquals(3000L,((Map<?,?>)item.get("representative")).get("media_offset_ms"));
        var locator=(Map<?,?>)item.get("source_locator");assertNull(locator.get("platform_id"));assertNull(locator.get("device_id"));assertEquals(0L,locator.get("media_epoch"));
    }
    @Test void simultaneouslyVisibleTargetsAreNotRejectedAcrossCameras(){
        Instant start=Instant.parse("2026-11-17T01:00:00Z");
        var anchor=new MtmcTracklets.Segment(List.of(shot("a",0,0,.9,start,Map.of(),Map.of()),shot("a",0,1000,.9,start.plusSeconds(1),Map.of(),Map.of())));
        var candidate=new MtmcTracklets.Segment(List.of(shot("b",0,0,.8,start,Map.of(),Map.of())));
        assertEquals("overlap",MtmcTracklets.temporal(anchor,candidate));assertTrue(MtmcTracklets.withinWindow(anchor,candidate,1));
        var item=MtmcTracklets.freeze(anchor,candidate,new EvidenceFusionMtmcStrategy(),600);assertTrue(((List<?>)item.get("conflicts")).isEmpty());
        assertEquals("camera_topology_unverified",item.get("geography_state"));
    }
    @Test void verifiedWindowFiltersOnlyKnownClockAndKeepsBeforeAfterRelations(){
        Instant start=Instant.parse("2026-11-17T01:00:00Z");
        var anchor=new MtmcTracklets.Segment(List.of(shot("a",0,0,.9,start,Map.of(),Map.of())));
        var after=new MtmcTracklets.Segment(List.of(shot("b",0,0,.8,start.plusSeconds(10),Map.of(),Map.of())));
        var before=new MtmcTracklets.Segment(List.of(shot("b",0,0,.8,start.minusSeconds(10),Map.of(),Map.of())));
        assertEquals("after",MtmcTracklets.temporal(anchor,after));assertEquals("before",MtmcTracklets.temporal(anchor,before));
        assertFalse(MtmcTracklets.withinWindow(anchor,after,9));assertTrue(MtmcTracklets.withinWindow(anchor,after,10));
        assertEquals(-10.,MtmcTracklets.delta(anchor,before));
    }
    @Test void fusionUsesConfirmedPlateEvidenceButRetainsConflictingCandidates(){
        var left=shot("a",0,0,.9,null,Map.of("vehicle_color","白色"),Map.of("state","ready","number","闽A12345"));
        var same=shot("b",0,0,.8,null,Map.of("vehicle_color","white"),Map.of("state","ready","number","闽A12345"));
        var different=shot("b",0,0,.8,null,Map.of("vehicle_color","黑色"),Map.of("state","ready","number","闽B12345"));
        var anchor=new MtmcTracklets.Segment(List.of(left));var equal=MtmcTracklets.evidence(anchor,new MtmcTracklets.Segment(List.of(same)),600);var conflict=MtmcTracklets.evidence(anchor,new MtmcTracklets.Segment(List.of(different)),600);
        assertEquals("matches",equal.plateRelation());assertEquals(1,equal.attributeMatches());assertEquals("conflicts",conflict.plateRelation());
        var strategy=new EvidenceFusionMtmcStrategy();assertTrue(strategy.evaluate(equal).score()>strategy.evaluate(conflict).score());assertFalse(strategy.evaluate(conflict).conflicts().isEmpty());
        assertEquals(.8,new AppearanceMtmcStrategy().evaluate(conflict).score());
    }
    @Test void inconsistentTrackAttributesAndPlatesDoNotBecomePositiveEvidence(){
        var anchor=new MtmcTracklets.Segment(List.of(shot("a",0,0,.9,null,Map.of("vehicle_color","白色"),Map.of("state","ready","number","闽A12345"))));
        var candidate=new MtmcTracklets.Segment(List.of(shot("b",0,0,.8,null,Map.of("vehicle_color","白色"),Map.of("state","ready","number","闽A12345")),shot("b",0,1000,.9,null,Map.of("vehicle_color","黑色"),Map.of("state","ready","number","闽B12345"))));
        var evidence=MtmcTracklets.evidence(anchor,candidate,600);assertEquals(0,evidence.attributeMatches());assertEquals(0,evidence.attributeConflicts());assertEquals("inconsistent_track",evidence.plateRelation());
    }
    @Test void appearanceAggregationUsesSeveralFramesAndNeverMixesTypesOrCameras(){
        var segment=new MtmcTracklets.Segment(List.of(shot("b",0,0,.9),shot("b",0,1000,.8),shot("b",0,2000,.7),shot("b",0,3000,.1)));
        assertEquals(.8,segment.appearance(),.000001);assertEquals(.9,segment.representative().hit().score());
        var frozen=MtmcTracklets.freeze(new MtmcTracklets.Segment(List.of(shot("a",0,0,.9))),segment,new AppearanceMtmcStrategy(),600);
        assertEquals(4,frozen.get("observed_frames"));assertTrue(((List<?>)frozen.get("sightings")).size()>1);
    }
}
