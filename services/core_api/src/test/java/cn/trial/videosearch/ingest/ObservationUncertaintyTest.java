package cn.trial.videosearch.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ObservationUncertaintyTest {
    @Test void colorNormalizationOnlyChangesSearchProjectionAndKeepsRawFactsForReplay() {
        var raw=Map.<String,Object>of("vehicle_color", "white", "transport_type", "truck");
        var indexed=ObservationStore.indexedAttributes(raw,List.of());
        assertEquals("白色",indexed.get("vehicle_color"));
        assertEquals("white",raw.get("vehicle_color"));
        assertEquals("truck",indexed.get("transport_type"));
        assertFalse(ObservationStore.indexedAttributes(raw,List.of("vehicle_color")).containsKey("vehicle_color"));
    }
    @Test void uncertainVehicleColorIsStoredForReviewButExcludedFromSearchEvidence() {
        var attrs = Map.<String,Object>of("vehicle_color", "白色", "transport_type", "轿车");
        assertEquals(Map.of("transport_type", "轿车"), ObservationStore.indexedAttributes(attrs, List.of("vehicle_color")));
        assertEquals(attrs, ObservationStore.indexedAttributes(attrs, List.of()));
        assertDoesNotThrow(() -> ObservationStore.validateObject(new ObservationStore.VisibleObject(
                "vehicle", attrs, null, null, List.of("vehicle_color"))));
        assertThrows(IllegalArgumentException.class, () -> ObservationStore.validateObject(new ObservationStore.VisibleObject(
                "person", attrs, null, null, List.of())));
    }
    @Test void validatesBoundsWithoutInventingOrAssumingCoordinateUnits() {
        assertDoesNotThrow(() -> ObservationStore.validateBoundingBox(null));
        assertDoesNotThrow(() -> ObservationStore.validateBoundingBox(List.of(10.0, 20.0, 60.0, 100.0)));
        assertThrows(IllegalArgumentException.class, () -> ObservationStore.validateBoundingBox(List.of(10.0, 20.0, 5.0, 100.0)));
        assertThrows(IllegalArgumentException.class, () -> ObservationStore.validateBoundingBox(List.of(0.0, 0.0, Double.NaN, 10.0)));
        assertThrows(IllegalArgumentException.class, () -> ObservationStore.validateBoundingBox(List.of(0.0, 1.0)));
    }

    @Test void normalizesKnownUncertaintyAliasesWithoutInventingValues() {
        assertEquals(List.of(), ObservationStore.normalizedUncertain(null));
        assertEquals(List.of("lower_color", "carrying"), ObservationStore.normalizedUncertain(
                List.of("lower_clothing_color", "lower_color", "carrying")));
        assertThrows(IllegalArgumentException.class, () -> ObservationStore.normalizedUncertain(List.of(" ")));
    }
}
