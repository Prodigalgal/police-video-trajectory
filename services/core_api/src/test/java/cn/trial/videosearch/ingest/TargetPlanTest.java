package cn.trial.videosearch.ingest;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TargetPlanTest {
    private Map<String,Object> target() {
        return new HashMap<>(Map.of("target_id",UUID.randomUUID().toString(),"track_id",UUID.randomUUID().toString(),
                "object_type","person","class_name","person","confidence",.9,"association","local_geometry_candidate",
                "bbox_xyxy",List.of(10.,20.,30.,40.),"bbox_normalized",List.of(.1,.2,.3,.4)));
    }
    private Map<String,Object> plan(List<Map<String,Object>> targets) {
        return new HashMap<>(Map.of("version","targets-v1","strategy","frame_batch","detection_state","complete",
                "image_width",100,"image_height",100,"targets",targets));
    }
    @Test void preservesExactTargetBinding() {
        var targets=List.of(target());
        assertEquals(targets,TargetPipeline.targets(plan(targets)));
    }
    @Test void rejectsDuplicateIdsAndIncorrectUnits() {
        var item=target();
        assertThrows(IllegalArgumentException.class,()->TargetPipeline.targets(plan(List.of(item,item))));
        item.put("bbox_normalized",List.of(10.,20.,30.,40.));
        assertThrows(IllegalArgumentException.class,()->TargetPipeline.targets(plan(List.of(item))));
    }
    @Test void rejectsMisclassifiedVehiclesAndNonfiniteScores() {
        var item=target(); item.put("class_name","car");
        assertThrows(IllegalArgumentException.class,()->TargetPipeline.targets(plan(List.of(item))));
        item.put("object_type","vehicle"); item.put("confidence",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->TargetPipeline.targets(plan(List.of(item))));
    }
    @Test void unknownDetectorStillHasSceneDispatch() {
        var value=plan(List.of()); value.put("detection_state","unknown");
        assertTrue(TargetPipeline.targets(value).isEmpty());
    }
}
