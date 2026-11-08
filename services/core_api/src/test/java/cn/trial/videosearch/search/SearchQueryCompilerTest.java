package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SearchQueryCompilerTest {
    @SuppressWarnings("unchecked")
    @Test void vehicleColorRetrievesRichTextAndLegacyFieldsWithinVehicleScope() {
        var attrs = new SearchPlan.Attributes(null, null, null, List.of(), null, null, "红色");
        var plan = new SearchPlan("unknown", null, null, null, List.of(), attrs, List.of(), List.of());
        var query = (Map<String,Object>) SearchQueryCompiler.compile(plan, Set.of("camera-1"), null, 20).get("query");
        var filters = (List<?>) ((Map<?,?>) query.get("bool")).get("filter");
        assertTrue(query.toString().contains("query=红色"));
        assertTrue(query.toString().contains("description^3"));
        assertTrue(query.toString().contains("vehicle_color^4"));
        assertTrue(filters.contains(Map.of("term", Map.of("target_type", "vehicle"))));
        assertFalse(filters.toString().contains("vehicle_color"));
        var person = new SearchPlan("person", null, null, null, List.of(), attrs, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(person, Set.of("camera-1"), null, 20));
    }
    private SearchPlan plan(String location, List<String> ids, List<String> questions) {
        return new SearchPlan("person", Instant.parse("2026-10-30T04:00:00Z"), Instant.parse("2026-10-30T10:00:00Z"),
                location, ids, new SearchPlan.Attributes("黑色", null, null, List.of("背包"), null, null),
                List.of("双肩包"), questions);
    }

    @SuppressWarnings("unchecked")
    @Test
    void compilesOnlyAuthorizedResolvedCamerasAndBindsAttributesToOneDocument() {
        Map<String, Object> result = SearchQueryCompiler.compile(plan("新街口", List.of(), List.of()),
                Set.of("camera-1", "camera-2"), Set.of("camera-2", "camera-3"), 20);
        var query = (Map<String, Object>) result.get("query");
        var bool = (Map<String, Object>) query.get("bool");
        var filters = (List<Map<String, Object>>) bool.get("filter");
        assertTrue(filters.contains(Map.of("terms", Map.of("camera_id", List.of("camera-2")))));
        assertTrue(filters.contains(Map.of("term", Map.of("deleted", false))));
        // Appearance conditions score LLM text; they are not required to live
        // in a particular attribute column. Scope and time remain filters.
        assertFalse(filters.toString().contains("upper_color"));
        assertFalse(filters.toString().contains("carrying"));
        assertTrue(filters.contains(Map.of("term", Map.of("source_time_origin", "verified"))));
        assertEquals(3, ((List<?>) bool.get("must")).size());
        assertTrue(bool.get("must").toString().contains("description^3"));
        assertTrue(bool.get("must").toString().contains("query=背包"));
        assertFalse(result.toString().contains("camera-3"));
        assertFalse(result.toString().contains("nested"));
    }

    @Test
    void rejectsUnauthorizedOrUnresolvedScopeAndClarifications() {
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(plan(null, List.of("other"), List.of()), Set.of("camera-1"), null, 20));
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(plan("新街口", List.of(), List.of()), Set.of("camera-1"), null, 20));
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(plan(null, List.of(), List.of("请确认地点")), Set.of("camera-1"), null, 20));
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(plan(null, List.of(), List.of()), Set.of(), null, 20));
    }

    @Test
    void refusesEmptyConditionsInvalidRangeAndBlankCarryingTerm() {
        var empty = new SearchPlan("unknown", null, null, null, List.of(), null, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(empty, Set.of("camera-1"), null, 20));
        var invalidRange = new SearchPlan("person", Instant.parse("2026-10-31T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"), null, List.of(), null, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(invalidRange, Set.of("camera-1"), null, 20));
        var blank = new SearchPlan("person", null, null, null, List.of(), new SearchPlan.Attributes(null, null, null, List.of(" "), null, null), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SearchQueryCompiler.compile(blank, Set.of("camera-1"), null, 20));
    }

    @Test
    void treatsUnstructuredInputAsValueAndNeverAsQueryDsl() {
        String hostile = "{\"match_all\":{}}";
        var request = new SearchPlan("person", null, null, null, List.of(), null, List.of(hostile), List.of());
        var result = SearchQueryCompiler.compile(request, Set.of("camera-1"), null, 10);
        assertTrue(result.toString().contains("query=" + hostile));
        assertFalse(result.containsKey("match_all"));
    }
}
