package cn.trial.videosearch.search;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchCursorCodecTest {
    private final Clock now=Clock.fixed(Instant.parse("2026-11-05T08:00:00Z"),ZoneOffset.UTC);
    private final SearchCursorCodec codec=new SearchCursorCodec("private-search-token","scope-01-04",now);
    private SearchCursorCodec.State state() {
        return new SearchCursorCodec.State(SimpleSearchPlanner.parse("白色 车").orElseThrow(),"opaque-pit-id",
                List.of(6.1,123456L,"246d6b91-b6ef-3b58-8d4b-1c355b5dd35f",42L),20,2,false,now.instant().getEpochSecond()+900,"a".repeat(64));
    }
    @Test void continuationPreservesPlanAndSortWithoutInvokingAQueryModel() {
        var original=state();var token=codec.encode(original,"白色 车");var decoded=codec.decode(token,"白色 车");
        assertEquals(original.plan(),decoded.plan());assertEquals(original.pit(),decoded.pit());
        assertEquals(original.after().toString(),decoded.after().toString());assertEquals(2,decoded.page());assertEquals(20,decoded.size());
    }
    @Test void tamperingQueryScopeAndCredentialRevisionAreRejected() {
        String token=codec.encode(state(),"白色 车");
        assertThrows(SearchCursorCodec.Invalid.class,()->codec.decode("X"+token.substring(1),"白色 车"));
        assertThrows(SearchCursorCodec.Invalid.class,()->codec.decode(token,"红色 车"));
        assertThrows(SearchCursorCodec.Invalid.class,()->new SearchCursorCodec("private-search-token","scope-01-06",now).decode(token,"白色 车"));
        assertThrows(SearchCursorCodec.Invalid.class,()->new SearchCursorCodec("rotated-token","scope-01-04",now).decode(token,"白色 车"));
    }
    @Test void expiredSnapshotsFailExplicitlyInsteadOfBecomingEmptyResults() {
        String token=codec.encode(state(),"白色 车");
        var later=new SearchCursorCodec("private-search-token","scope-01-04",Clock.offset(now,Duration.ofSeconds(901)));
        assertEquals("search_cursor_expired",assertThrows(SearchCursorCodec.Invalid.class,()->later.decode(token,"白色 车")).code);
    }
    @Test void malformedSortAndOversizedStateCannotReachElasticsearch() {
        assertThrows(IllegalArgumentException.class,()->SearchCursorCodec.validateSort(List.of(1,2,Map.of("match_all",Map.of()),4)));
        assertThrows(IllegalArgumentException.class,()->SearchCursorCodec.validateSort(List.of(Double.NaN,2,UUID.randomUUID().toString(),4)));
        assertThrows(SearchCursorCodec.Invalid.class,()->codec.decode("x".repeat(32769),"白色 车"));
    }
    @Test void fingerprintDoesNotDependOnMapIterationOrder() {
        var first=new LinkedHashMap<String,Object>();first.put("a",1);first.put("b",List.of(Map.of("z",2,"x",3)));
        var second=new LinkedHashMap<String,Object>();second.put("b",List.of(Map.of("x",3,"z",2)));second.put("a",1);
        assertEquals(SearchCursorCodec.fingerprint(first),SearchCursorCodec.fingerprint(second));
    }
}
