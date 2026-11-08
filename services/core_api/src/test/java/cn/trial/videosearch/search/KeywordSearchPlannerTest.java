package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeywordSearchPlannerTest {
    @Test void literalModeKeepsTheCompleteInputAndAlwaysRetainsAuthorizedScope() {
        String text="昨天在路口的白色汽车，不要摩托车";
        var plan=KeywordSearchPlanner.plan("  "+text+"  ");
        assertEquals(List.of(text),plan.textTerms());assertEquals("unknown",plan.targetType());
        assertNull(plan.start());assertNull(plan.end());assertNull(plan.locationText());assertNull(plan.attributes());
        var query=SearchQueryCompiler.compile(plan,Set.of("recording-01"),null,20).toString();
        assertTrue(query.contains("camera_id=[recording-01]"));assertTrue(query.contains("deleted=false"));assertTrue(query.contains(text));
        assertFalse(query.contains("must_not"));assertFalse(query.contains("query_string"));
    }

    @Test void modesCannotReplaceSignedContinuationsOrSilentlyTruncateInput() {
        for(String query:Arrays.asList(null,"  ","车".repeat(201),"🚗".repeat(101)))
            assertThrows(IllegalArgumentException.class,()->KeywordSearchPlanner.plan(query));
        assertDoesNotThrow(()->KeywordSearchPlanner.plan("车".repeat(200)));
        assertThrows(IllegalArgumentException.class,()->KeywordSearchPlanner.validate(null,"车",null,null));
        assertThrows(IllegalArgumentException.class,()->KeywordSearchPlanner.validate("dsl","车",null,null));
        assertThrows(IllegalArgumentException.class,()->KeywordSearchPlanner.validate("keyword","车","cursor",null));
        var edit=new SearchRefinement.Command("context","choose_cameras",null,List.of("recording-01"));
        assertThrows(IllegalArgumentException.class,()->KeywordSearchPlanner.validate("keyword","车",null,edit));
        assertDoesNotThrow(()->KeywordSearchPlanner.validate("natural","车","cursor",null));
    }

    @Test void existingPlanSignaturesAndRefinementPreserveLiteralTerms() {
        var plan=KeywordSearchPlanner.plan("白色汽车");
        var restored=SearchPlanParser.parse(SearchPlanParser.document(plan));
        var command=new SearchRefinement.Command("context","choose_cameras",null,List.of("recording-02"));
        var narrowed=SearchRefinement.apply(restored,command,Set.of("recording-01","recording-02"));
        assertEquals(List.of("白色汽车"),narrowed.textTerms());assertEquals(List.of("recording-02"),narrowed.cameraIds());
        assertEquals("unknown",narrowed.targetType());assertNull(narrowed.start());
        assertThrows(IllegalArgumentException.class,()->SearchQueryCompiler.compile(narrowed,Set.of("recording-01"),null,20));
    }
}
