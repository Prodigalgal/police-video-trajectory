package cn.trial.videosearch.search;

import cn.trial.videosearch.http.SecureJsonClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchPagingTest {
    private Map<String,Object> hit(int n) {
        return Map.of("_source",Map.of("observation_id",String.format("00000000-0000-0000-0000-%012d",n)),"sort",List.of(1.0,123456L,String.format("00000000-0000-0000-0000-%012d",n),n));
    }
    private Map<String,Object> response(List<Map<String,Object>> hits) {
        return Map.of("pit_id","renewed-pit","timed_out",false,"_shards",Map.of("failed",0),"hits",Map.of("total",Map.of("value",5,"relation","eq"),"hits",hits));
    }
    @SuppressWarnings("unchecked")
    @Test void forwardAndBackwardPagesUseSnapshotAndStableSeekInsteadOfOffsets() {
        var es=mock(SecureJsonClient.class);var codec=new SearchCursorCodec("key","scope");
        var service=new SearchPaging(es,"auth","police-video-observations",codec);var plan=SimpleSearchPlanner.parse("白车").orElseThrow();
        var query=SearchQueryCompiler.compile(plan,Set.of("recording-01"),null,2);
        when(es.post(contains("/_pit?"),isNull(),anyString(),any())).thenReturn(Map.of("id","initial-pit"));
        when(es.post(startsWith("/_search?"),anyMap(),anyString(),any())).thenReturn(response(List.of(hit(1),hit(2))),response(List.of(hit(2),hit(1))));
        var first=service.fetch("白车",plan,query,2,null);
        assertEquals(1L,first.pagination().get("page"));assertNull(first.pagination().get("previous_cursor"));
        var forward=codec.decode((String)first.pagination().get("next_cursor"),"白车");assertEquals("renewed-pit",forward.pit());assertEquals(2,forward.page());
        var backward=new SearchCursorCodec.State(plan,forward.pit(),forward.after(),2,1,true,forward.expires(),forward.fingerprint());
        var previous=service.fetch("白车",plan,query,2,backward);
        var rows=(List<Map<String,Object>>)((Map<?,?>)previous.result().get("hits")).get("hits");
        assertEquals(hit(1),rows.getFirst());assertEquals(hit(2),rows.getLast());
        verify(es,times(1)).post(contains("/_pit?"),isNull(),anyString(),any());
        verify(es,times(1)).post(startsWith("/_search?"),argThat(body->body.get("sort").equals(SearchPaging.sort(true)) && body.containsKey("search_after") && !body.containsKey("from")),anyString(),any());
    }
    @Test void scopeChangeAndExpiredPitCannotSilentlyRestartAtPageOne() {
        var es=mock(SecureJsonClient.class);var codec=new SearchCursorCodec("key","scope");var service=new SearchPaging(es,"auth","police-video-observations",codec);
        var plan=SimpleSearchPlanner.parse("白车").orElseThrow();var query=SearchQueryCompiler.compile(plan,Set.of("recording-01"),null,20);
        var state=new SearchCursorCodec.State(plan,"expired",List.of(),20,2,false,codec.now()+900,SearchCursorCodec.fingerprint(query.get("query")));
        when(es.post(startsWith("/_search?"),anyMap(),anyString(),any())).thenThrow(new SecureJsonClient.UpstreamFailure(404));
        assertEquals("search_cursor_expired",assertThrows(SearchCursorCodec.Invalid.class,()->service.fetch("白车",plan,query,20,state)).code);
        var other=SearchQueryCompiler.compile(plan,Set.of("recording-02"),null,20);
        assertEquals("invalid_search_cursor",assertThrows(SearchCursorCodec.Invalid.class,()->service.fetch("白车",plan,other,20,state)).code);
        verify(es,never()).post(contains("/_pit?"),isNull(),anyString(),any());
    }
}
