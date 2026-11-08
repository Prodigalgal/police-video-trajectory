package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistEvidenceTest {
    private final UUID job=UUID.randomUUID(),frame=UUID.randomUUID(),input=UUID.randomUUID(),observation=UUID.randomUUID(),event=UUID.randomUUID();
    private Map<String,Object> row(){return Map.of("job_id",job,"frame_id",frame,"input_id",input,"camera_id","camera-01","input_manifest_sha256","a".repeat(64),"result_sha256","b".repeat(64));}
    private Map<String,Object> decision(){return Map.of("event_id",event.toString(),"frame_id",frame.toString(),"jpeg_sha256","c".repeat(64),"plate_bbox_xyxy",List.of(100,200,200,240));}
    private Map<String,Object> result(){return Map.of("evidence_sha256","c".repeat(64),"image_width",1000,"image_height",600,"regions",List.of(Map.of("bbox_xyxy",List.of(100,200,200,240)),Map.of("bbox_xyxy",List.of(700,200,800,240))));}
    @Test void eligibilityUsesTheSameSpatialRuleAsConfirmation(){var bound=PlateReviewTarget.bind(decision(),row(),result());assertEquals(event,bound.eventId());assertEquals(List.of(0),bound.eligible());assertEquals(List.of(0),bound.view(row(),result()).get("eligible_region_indices"));}
    @Test void stalePixelsForeignFrameAndInvalidGeometryFailClosed(){
        for(var change:List.of(Map.of("frame_id",UUID.randomUUID().toString()),Map.of("jpeg_sha256","d".repeat(64)),Map.of("plate_bbox_xyxy",List.of(-1,0,50,50)))){
            var value=new LinkedHashMap<String,Object>(decision());value.putAll(change);assertEquals("plate_review_unavailable",assertThrows(InputRejected.class,()->PlateReviewTarget.bind(value,row(),result())).code);
        }
    }
    @Test void frozenSourceNavigationDoesNotInventObservationOrFrame(){
        var anchor=Map.<String,Object>of("observation_id",observation.toString(),"frame_id",frame.toString(),"record_version",7L,"target_type","vehicle");
        var manifest=Map.<String,Object>of("anchor",anchor,"source",Map.of("camera_id","camera-01"));
        var verified=new AnalysisInputs.Verified(manifest,"unused","a".repeat(64),null);var source=SpecialistResultSource.describe(row(),verified);
        assertEquals(observation.toString(),source.get("observation_id"));assertEquals(7L,source.get("record_version"));assertFalse(source.containsKey("source_ref"));
        var foreign=new LinkedHashMap<>(row());foreign.put("frame_id",UUID.randomUUID());assertThrows(InputRejected.class,()->SpecialistResultSource.describe(foreign,verified));
    }
}
