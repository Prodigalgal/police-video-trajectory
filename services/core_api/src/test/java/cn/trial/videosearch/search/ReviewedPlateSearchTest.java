package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedPlateSearchTest {
    private SearchPlan plan(){return new SearchPlan("vehicle",null,null,null,List.of(),null,List.of(),List.of(),"闽E12345");}
    @Test void completeOrdinaryAndEnergyPlatesNormalizeWithoutGuessing(){
        assertEquals("闽E12345",PlateNumbers.normalize("闽ｅ·１２３４５"));
        assertEquals("京AD12345",PlateNumbers.normalize("京A D12345"));
        assertEquals("粤B12345F",PlateNumbers.normalize("粤B12345F"));
        for(String invalid:List.of("闽E1234","闽E123456","闽E12O45","闽I12345","闽E12?45","闽E1234567"))assertThrows(IllegalArgumentException.class,()->PlateNumbers.normalize(invalid));
    }
    @Test void extractionClarifiesPartialMultipleAndNegatedPlates(){
        var plate=PlateNumbers.extract("帮我搜索车牌 闽e·12345 的记录");
        assertEquals("闽E12345",plate.number());assertTrue(plate.remainder().isBlank());
        assertEquals("昨天白色车",PlateNumbers.extract("昨天白色车 闽E12345").remainder());
        assertFalse(PlateNumbers.extract("车牌闽E123").questions().isEmpty());
        assertFalse(PlateNumbers.extract("闽E12345 或 闽E54321").questions().isEmpty());
        assertNull(PlateNumbers.extract("排除闽E12345").number());
        assertTrue(PlateNumbers.extract("白色车").questions().isEmpty());
    }
    @Test void signedPlanIsBackwardCompatibleAndRefinementsRetainPlateScope(){
        var old=new SearchPlan("vehicle",null,null,null,List.of(),null,List.of(),List.of());
        assertFalse(SearchPlanParser.document(old).containsKey("plate_number"));
        assertNull(SearchPlanParser.parse(SearchPlanParser.document(old)).plateNumber());
        assertEquals("闽E12345",SearchPlanParser.parse(SearchPlanParser.document(plan())).plateNumber());
        assertEquals(SearchPlanParser.document(plan()),SearchPlanParser.document(SearchPlanParser.parse(SearchPlanParser.document(plan()))));
        var selected=SearchRefinement.apply(plan(),new SearchRefinement.Command("signed","choose_cameras",null,List.of("camera-1")),Set.of("camera-1"));
        assertEquals("闽E12345",selected.plateNumber());
        assertFalse(SearchRefinement.conditions(selected,Map.of()).stream().filter(c->c.id().equals("target")).findFirst().orElseThrow().removable());
        assertNull(SearchRefinement.apply(selected,new SearchRefinement.Command("signed","remove","plate",null),Set.of("camera-1")).plateNumber());
    }
    @Test void compilerUsesExactKeywordFilterAndEvidenceDoesNotInventConfidence(){
        var query=SearchQueryCompiler.compile(plan(),Set.of("camera-1"),null,20);
        var filters=(List<?>)((Map<?,?>)((Map<?,?>)query.get("query")).get("bool")).get("filter");
        assertTrue(filters.contains(Map.of("term",Map.of("reviewed_plate_number","闽E12345"))));
        var hit=SearchMatchEvidence.annotate(List.of(Map.of("observation_id",UUID.randomUUID().toString())),plan()).getFirst();
        assertEquals("reviewed_plate_exact",hit.get("match_basis"));assertFalse(hit.containsKey("confidence"));
        assertThrows(IllegalArgumentException.class,()->SearchQueryCompiler.compile(new SearchPlan("person",null,null,null,List.of(),null,List.of(),List.of(),"闽E12345"),Set.of("camera-1"),null,20));
    }
}
