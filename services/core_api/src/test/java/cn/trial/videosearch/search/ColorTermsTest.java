package cn.trial.videosearch.search;

import cn.trial.videosearch.attributes.ColorTerms;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColorTermsTest {
    @Test void englishAndChineseColorConditionsHaveTheSameVehicleAndClothingMeaning() {
        var attrs=new SearchPlan.Attributes("BLACK", "蓝", null, List.of(), null, null, " White ");
        assertEquals("黑色", attrs.upperColor());
        assertEquals("蓝色", attrs.lowerColor());
        assertEquals("白色", attrs.vehicleColor());
        assertEquals("灰色", ColorTerms.canonical("grey"));
        assertEquals("灰色", ColorTerms.canonical("gray"));
        assertNotEquals(ColorTerms.canonical("silver"),ColorTerms.canonical("gray"));
    }

    @Test void unknownCompositeAndMissingColorsAreNeverGuessed() {
        assertNull(ColorTerms.canonical(null));
        assertEquals("white/silver", ColorTerms.canonical("white/silver"));
        assertEquals("unknown", ColorTerms.canonical("unknown"));
        assertEquals("深色", ColorTerms.canonical("dark"));
        assertNotEquals("黑色", ColorTerms.canonical("dark"));
    }
}
