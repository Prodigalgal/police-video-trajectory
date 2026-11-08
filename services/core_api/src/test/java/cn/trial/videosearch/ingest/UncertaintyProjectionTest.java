package cn.trial.videosearch.ingest;

import cn.trial.videosearch.attributes.UncertaintyTerms;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class UncertaintyProjectionTest {
    @Test void chineseAndProviderPathsCannotBecomeCertainSearchEvidence() {
        var raw = Map.<String,Object>of("upper_color", "black", "lower_color", "blue", "carrying", List.of("背包"), "visible_action", "行走");
        var uncertain = List.of("上衣颜色", "attributes.lower_color", "carrying[0]", "可见动作");
        assertEquals(Map.of(), ObservationStore.indexedAttributes(raw, uncertain));
        assertEquals("black", raw.get("upper_color"));
        assertEquals(List.of("背包"), raw.get("carrying"));
        assertEquals(List.of("上衣颜色", "attributes.lower_color", "carrying[0]", "可见动作"), uncertain);
    }
    @Test void genericColorUncertaintySuppressesOnlyColors() {
        var raw = Map.<String,Object>of("upper_color", "black", "lower_color", "blue", "vehicle_color", "white", "transport_type", "汽车");
        assertEquals(Map.of("transport_type", "汽车"), ObservationStore.indexedAttributes(raw, List.of("颜色")));
        assertEquals(Set.of("upper_color", "lower_color", "vehicle_color"), UncertaintyTerms.suppressedAttributes(List.of("attributes.color")));
        assertEquals(Set.of("upper_color", "lower_color"), UncertaintyTerms.suppressedAttributes(List.of("衣服颜色")));
    }
    @Test void unrelatedUnknownsDoNotEraseKnownAttributesOrRewriteRawUncertainty() {
        var raw = Map.<String,Object>of("upper_color", "black", "transport_type", "truck");
        assertEquals(Map.of("upper_color", "黑色", "transport_type", "truck"), ObservationStore.indexedAttributes(raw, List.of("性别", "品牌")));
        // Keep ingestion/replay representation compatible; this release changes
        // the search projection rather than rewriting stored source facts.
        assertEquals(List.of("上衣颜色", "color"), ObservationStore.normalizedUncertain(List.of("上衣颜色", "color")));
    }
}
