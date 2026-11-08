package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SearchMatchEvidenceTest {
    @Test void descriptionRecallIsVisibleAndNeverBackfillsColorFacts() {
        var source = Map.<String,Object>of("target_type", "vehicle", "description", "白色面包车后方被遮挡的车辆", "matched_queries", List.of("semantic_0"));
        var indexed = Map.<String,Object>of("target_type", "vehicle", "vehicle_color", "白色", "matched_queries", List.of("semantic_0"));
        var result = SearchMatchEvidence.annotate(List.of(source, indexed), SimpleSearchPlanner.parse("白色 车").orElseThrow());
        assertEquals("llm_semantics_bm25", result.getFirst().get("match_basis"));
        assertEquals(List.of("车身颜色 · 白色"), result.getFirst().get("match_reasons"));
        assertFalse(result.getFirst().containsKey("vehicle_color"));
        assertFalse(source.containsKey("match_basis"));
        assertEquals(List.of("车身颜色 · 白色"), result.getLast().get("match_reasons"));
        assertFalse(result.getFirst().containsKey("matched_queries"));
        assertEquals("llm_semantics_bm25", SearchMatchEvidence.summary(result).get("mode"));
    }
    @Test void generalVehicleSearchDoesNotInventAColorCondition() {
        var rows = List.of(Map.<String,Object>of("target_type", "vehicle", "description", "白色车辆"));
        var result = SearchMatchEvidence.annotate(rows, SimpleSearchPlanner.parse("查找车辆").orElseThrow());
        assertEquals("authorized_observation", result.getFirst().get("match_basis"));
        assertEquals(List.of(), result.getFirst().get("match_reasons"));
        assertFalse(result.getFirst().containsKey("vehicle_color"));
    }
}
