package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObservationDetailsTest {
    private Map<String,Object> binding() {
        var row=new HashMap<String,Object>();
        row.put("target_id",UUID.randomUUID());row.put("target_type","vehicle");row.put("detector_object_type","vehicle");row.put("class_name","car");
        row.put("payload_sha256","a".repeat(64));row.put("plan_text","{\"image_width\":1280,\"image_height\":720}");
        row.put("detector_bbox","[100,120,200,260]");row.put("observation_bbox","[100.0,120.0,200.0,260.0]");return row;
    }
    private EvidenceFiles.FrameFile frame() { return new EvidenceFiles.FrameFile(new byte[0],1280,720,"a".repeat(64)); }
    @Test void candidateOverlayRequiresTheRegisteredImageAndSameObjectBox() {
        var row=binding();var location=ObservationDetails.location(row,frame());
        assertEquals("bound_detection",location.get("status"));assertEquals("jpeg_pixels",location.get("coordinate_space"));
        row.put("payload_sha256","b".repeat(64));assertEquals("unavailable",ObservationDetails.location(row,frame()).get("status"));
        row=binding();row.put("plan_text","{\"image_width\":1280,\"image_height\":960}");assertEquals("image_dimensions_mismatch",ObservationDetails.location(row,frame()).get("reason"));
        row=binding();row.put("observation_bbox","[0,0,1,1]");assertEquals("box_binding_mismatch",ObservationDetails.location(row,frame()).get("reason"));
        row=binding();row.put("detector_object_type","person");assertEquals("target_type_mismatch",ObservationDetails.location(row,frame()).get("reason"));
    }
    @Test void legacyBoxesAreNotGuessedToBePixelsOrNormalizedCoordinates() {
        var row=binding();row.remove("target_id");row.put("observation_bbox","[0.1,0.1,0.5,0.5]");
        assertEquals("no_detector_binding",ObservationDetails.location(row,frame()).get("reason"));
        row=binding();row.put("detector_bbox","[100,120,2000,260]");row.put("observation_bbox","[100,120,2000,260]");
        assertEquals("invalid_box",ObservationDetails.location(row,frame()).get("reason"));
        assertEquals("image_unavailable",ObservationDetails.location(binding(),null).get("reason"));
    }
    @Test void outOfScopeObservationDoesNotReadFilesOrExposeItsSourceReference() throws Exception {
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(EvidenceFiles.class);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("camera_id","recording-05","source_ref","secret:must-stay-internal")));
        var details=new ObservationDetails(db,search,files,mock(FrameRecordings.class),"missing",new cn.trial.videosearch.evidence.ReviewedPlateFacts(db,false));
        assertTrue(details.read(UUID.randomUUID()).isEmpty());verifyNoInteractions(files);
    }
    @Test void authenticationPrecedesDetailLookup() {
        var service=mock(ObservationDetails.class);var controller=new ObservationController(service,mock(SearchOrchestrator.class));
        assertEquals(401,controller.read(null,UUID.randomUUID()).block().getStatusCode().value());verifyNoInteractions(service);
    }
}
