package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchPlanParserTest {
    @Test void vehicleColorIsAcceptedForCarsAndRejectedForPeople() {
        var plan = valid(); plan.put("target_type", "vehicle"); plan.put("attributes", Map.of("vehicle_color", "红色", "carrying", List.of()));
        assertEquals("红色", SearchPlanParser.parse(plan).attributes().vehicleColor());
        plan.put("target_type", "person");
        assertThrows(IllegalArgumentException.class, () -> SearchPlanParser.parse(plan));
    }
    private Map<String, Object> valid() {
        var p = new HashMap<String, Object>();
        p.put("schema_version", "1.0");
        p.put("target_type", "person");
        p.put("time_range", Map.of("start", "2026-10-30T12:00:00+08:00", "end", "2026-10-30T18:00:00+08:00"));
        p.put("location_text", null);
        p.put("camera_ids", List.of());
        p.put("attributes", Map.of("carrying", List.of("背包"), "upper_color", "黑色"));
        p.put("text_terms", List.of());
        p.put("clarifications", List.of());
        return p;
    }

    @Test
    void parsesTimezoneAndObjectAttributes() {
        SearchPlan plan = SearchPlanParser.parse(valid());
        assertEquals(Instant.parse("2026-10-30T04:00:00Z"), plan.start());
        assertEquals("黑色", plan.attributes().upperColor());
        assertEquals(List.of("背包"), plan.attributes().carrying());
    }

    @Test
    void rejectsRawDslUnknownFieldsMissingTimezoneAndWrongTypes() {
        var raw = valid(); raw.put("query", Map.of("match_all", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SearchPlanParser.parse(raw));
        var naive = valid(); naive.put("time_range", Map.of("start", "2026-10-30T12:00:00"));
        assertThrows(IllegalArgumentException.class, () -> SearchPlanParser.parse(naive));
        var wrong = valid(); wrong.put("camera_ids", List.of(Map.of("match_all", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> SearchPlanParser.parse(wrong));
        var noAttrs = valid(); noAttrs.put("attributes", null);
        assertThrows(IllegalArgumentException.class, () -> SearchPlanParser.parse(noAttrs));
    }
}
