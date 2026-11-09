package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestedPlateTest {
    @Test void requestedAdmissionChecksCurrentVehicleRegionAndBudget(){
        var db=mock(JdbcTemplate.class);var admission=new SpecialistAdmission(db,true,"",300,2,mock(PlateEventResults.class));
        when(db.queryForList(startsWith("SELECT target_type"),any(Object[].class))).thenReturn(List.of(Map.of("target_type","vehicle","box","[10.2,20.1,300.1,220.8]")));
        when(db.queryForObject(startsWith("SELECT count(*)"),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var descriptor=Map.<String,Object>of("input_manifest_sha256","a".repeat(64),"frame_id",UUID.randomUUID().toString(),"camera_id","recording-01","operation","license_plate","image_width",640,"image_height",480,"evidence_sha256","b".repeat(64));
        var manifest=Map.<String,Object>of("anchor",Map.of("observation_id",UUID.randomUUID().toString(),"record_version",1));
        assertEquals("selected",admission.previewRequested(descriptor,manifest,"verify_vehicle").get("decision"));
        assertEquals(List.of(10L,20L,301L,221L),admission.approveRequested(descriptor,manifest,"verify_vehicle").summary().get("vehicle_bbox_xyxy"));
        when(db.queryForObject(startsWith("SELECT count(*)"),eq(Long.class),any(Object[].class))).thenReturn(2L);
        assertEquals("deferred",admission.previewRequested(descriptor,manifest,"verify_vehicle").get("decision"));
        assertThrows(InputRejected.class,()->admission.approveRequested(descriptor,manifest,"verify_vehicle"));
        when(db.queryForList(startsWith("SELECT target_type"),any(Object[].class))).thenReturn(List.of(Map.of("target_type","person","box","[10,20,300,220]")));
        assertEquals("skipped",admission.previewRequested(descriptor,manifest,"verify_vehicle").get("decision"));
    }
    @Test void requestedReviewCannotAssignNeighboringOrAmbiguousPlatesAndCreatesNoEvent(){
        String frame=UUID.randomUUID().toString(),sha="a".repeat(64);
        var decision=Map.<String,Object>of("selection_mode","requested","frame_id",frame,"jpeg_sha256",sha,"vehicle_bbox_xyxy",List.of(10,10,300,250));
        var job=Map.<String,Object>of("frame_id",UUID.fromString(frame));
        var first=Map.of("bbox_xyxy",List.of(20,200,100,230));var neighbor=Map.of("bbox_xyxy",List.of(320,200,400,230));
        var result=new HashMap<String,Object>(Map.of("image_width",640,"image_height",480,"evidence_sha256",sha,"regions",List.of(first,neighbor)));
        var target=PlateReviewTarget.requested(decision,job,result).orElseThrow();assertNull(target.eventId());assertEquals(List.of(0),target.eligible());
        result.put("regions",List.of(first,Map.of("bbox_xyxy",List.of(110,200,200,230))));assertTrue(PlateReviewTarget.requested(decision,job,result).isEmpty());
        result.put("regions",List.of(neighbor));assertTrue(PlateReviewTarget.requested(decision,job,result).isEmpty());
        assertTrue(SpecialistAdmission.requestedBox(List.of(-1,0,10,10),640,480).isEmpty());
    }
}
