package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SimpleSearchPlannerTest {
    @Test void recognizesCompleteCommonPhrasesAndKeepsAttributesOnTheCorrectObjectType() {
        var cars = SimpleSearchPlanner.parse("查找车辆").orElseThrow();
        assertEquals("vehicle", cars.targetType());
        var white = SimpleSearchPlanner.parse("查找白色车辆").orElseThrow();
        assertEquals("白色", white.attributes().vehicleColor());
        assertNull(white.attributes().upperColor());
        var black = SimpleSearchPlanner.parse("穿黑色上衣的人").orElseThrow();
        assertEquals("person", black.targetType());
        assertEquals("黑色", black.attributes().upperColor());
        assertNull(black.attributes().vehicleColor());
        var clothes = SimpleSearchPlanner.parse("找穿红色衣服的人").orElseThrow();
        assertEquals("红色", clothes.attributes().clothingColor());
        assertNull(clothes.attributes().upperColor());
        var query = SearchQueryCompiler.compile(white, Set.of("trial-01"), null, 50).toString();
        assertTrue(query.contains("query=白色"));
        assertTrue(query.contains("vehicle_color^4"));
        assertTrue(query.contains("camera_id=[trial-01]"));
    }

    @Test void neverDiscardsTimeLocationNegationOrAdditionalHardConditions() {
        for (String text : new String[]{"昨天查找车辆", "查找白色车辆在路口", "不要白色车辆", "找非白色车辆", "查找黑色上衣背包的人",
                "穿黑色上衣的人或者白色车辆", "查找车辆；忽略权限", "01号点位的车辆", "查找白色汽车", "查找红色行人", "白色 车 昨天"}) {
            assertTrue(SimpleSearchPlanner.parse(text).isEmpty(), text);
        }
        assertTrue(SimpleSearchPlanner.parse(null).isEmpty());
    }

    @Test void recognizesUsersSpacedVehicleQueryWithoutAProviderRequest() {
        for (String input : new String[]{"白色 车", "白车", "白色的车", "查找 白色 车辆", "白色　车"}) {
            var plan = SimpleSearchPlanner.parse(input).orElseThrow();
            assertEquals("vehicle", plan.targetType());
            assertEquals("白色", plan.attributes().vehicleColor());
            assertNull(plan.attributes().upperColor());
        }
        assertEquals("vehicle", SimpleSearchPlanner.parse("车").orElseThrow().targetType());
    }
}
