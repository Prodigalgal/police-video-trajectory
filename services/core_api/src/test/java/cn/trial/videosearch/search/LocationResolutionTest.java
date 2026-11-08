package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocationResolutionTest {
    private Map<String,Object> point(String id,String region) {var result=new HashMap<String,Object>();result.put("camera_id",id);result.put("location_key",region);return result;}
    @Test void uniqueRegisteredPointDoesNotRequireARegionToBeSearchable() {
        assertEquals(Set.of("recording-01"),SearchOrchestrator.resolveLocationRows(List.of(point("recording-01",null)),Set.of("recording-01")));
    }
    @Test void ambiguousUnregisteredRegionsAreClarifiedAndHiddenPointsExcluded() {
        var rows=List.of(point("recording-01",null),point("recording-02",null));
        assertEquals(Set.of(),SearchOrchestrator.resolveLocationRows(rows,Set.of("recording-01","recording-02")));
        assertEquals(Set.of("recording-01"),SearchOrchestrator.resolveLocationRows(rows,Set.of("recording-01")));
        assertEquals(Set.of("recording-01","recording-02"),SearchOrchestrator.resolveLocationRows(List.of(point("recording-01","北区"),point("recording-02","北区")),Set.of("recording-01","recording-02")));
    }
}
