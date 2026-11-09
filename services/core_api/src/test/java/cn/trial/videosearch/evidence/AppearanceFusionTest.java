package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.search.SearchQueryCompiler;
import cn.trial.videosearch.http.SecureJsonClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class AppearanceFusionTest {
    private static AppearanceIndex.Ranked hit(){return new AppearanceIndex.Ranked(Map.of("observation_id",UUID.randomUUID().toString(),"record_version",1L,"frame_id",UUID.randomUUID().toString(),"camera_id","one","target_type","vehicle"),Map.of(),"",.85);}
    private static AppearanceFusion.Semantic semantic(){return new AppearanceFusion.Semantic("completed",Map.of("vehicle_color","白色","transport_type","轿车"),List.of(),"vision-model","appearance-description-v1");}
    private static AppearanceIndex.Filter filter(){return new AppearanceIndex.Filter("vehicle",List.of("one"),null,null);}
    @Test void esIsSoftAndScopedToTheAlreadyRecalledCandidates(){
        var id=UUID.randomUUID().toString();var plan=AppearanceFusion.plan(semantic(),filter());var query=SearchQueryCompiler.appearanceMatches(plan,Set.of("one"),List.of(id));
        var bool=(Map<?,?>)((Map<?,?>)query.get("query")).get("bool");
        assertFalse(bool.containsKey("must"));assertEquals(1,bool.get("minimum_should_match"));assertEquals(2,((List<?>)bool.get("should")).size());
        assertTrue(bool.get("filter").toString().contains(id));
    }
    @Test void semanticMatchCanReorderButCannotRemoveTheOtherVisualCandidate(){
        var search=mock(SearchOrchestrator.class);var first=hit();var second=hit();var match=new HashMap<String,Object>(second.source());match.put("matched_queries",List.of("semantic_0"));
        when(search.appearanceSemanticMatches(any(),anyList())).thenReturn(List.of(match));
        var result=new AppearanceFusion(search).apply(semantic(),List.of(first,second),filter());
        assertEquals(2,result.rankings().size());assertEquals(1,result.summary().get("semantic_matches"));
        assertTrue((double)result.rankings().get(second.source().get("observation_id")).get("score")>(double)result.rankings().get(first.source().get("observation_id")).get("score"));
        assertNull(result.rankings().get(first.source().get("observation_id")).get("semantic_rank"));
    }
    @Test void staleEsVersionDoesNotReceiveSemanticCredit(){
        var search=mock(SearchOrchestrator.class);var first=hit();var match=new HashMap<String,Object>(first.source());match.put("record_version",2L);
        when(search.appearanceSemanticMatches(any(),anyList())).thenReturn(List.of(match));
        var result=new AppearanceFusion(search).apply(semantic(),List.of(first),filter());assertTrue(result.rankings().isEmpty());assertEquals(0,result.summary().get("semantic_matches"));
    }
    @Test void uncertainAttributesAndEsFailurePreserveTheVisualFallback(){
        var search=mock(SearchOrchestrator.class);var fusion=new AppearanceFusion(search);var unknown=new AppearanceFusion.Semantic("completed",Map.of("vehicle_color","白色"),List.of("vehicle_color"),"vision-model","appearance-description-v1");
        assertEquals("no_attributes",fusion.apply(unknown,List.of(hit()),filter()).summary().get("state"));verifyNoInteractions(search);
        when(search.appearanceSemanticMatches(any(),anyList())).thenThrow(new SecureJsonClient.UpstreamFailure());
        var result=fusion.apply(semantic(),List.of(hit()),filter());assertEquals("search_unavailable",result.summary().get("state"));assertTrue(result.rankings().isEmpty());
    }
    @Test void missingOrUnknownSemanticsCannotBecomeAnArbitraryQuery(){
        assertEquals("disabled",AppearanceFusion.validated(null).state());
        assertThrows(IllegalArgumentException.class,()->AppearanceFusion.validated(new AppearanceFusion.Semantic("completed",Map.of("query",Map.of("match_all",Map.of())),List.of(),"vision","appearance-description-v1")));
    }
    @Test void queryFingerprintAcceptsTypedSemanticsIncludingTheUnavailableFallback(){
        var db=mock(JdbcTemplate.class);var scope=mock(SearchOrchestrator.class);var catalog=mock(AppearanceCatalog.class);
        String profileSha="a".repeat(64);var profile=new AppearanceCatalog.Profile("visual",profileSha,384,Map.of());
        when(catalog.current()).thenReturn(new AppearanceCatalog.Snapshot(1,"visual",Map.of("visual",profile)));
        when(scope.readableCameras()).thenReturn(Set.of("one"));when(scope.mayReadCamera("one")).thenReturn(true);
        var service=new AppearanceSearch(db,mock(AppearanceJobs.class),catalog,mock(AppearanceSource.class),mock(AppearanceIndex.class),scope,new AppearanceFusion(scope),mock(PlatformTransactionManager.class));
        for(var semantics:List.of(semantic(),new AppearanceFusion.Semantic("unavailable",Map.of(),List.of(),null,null))){
            UUID id=UUID.randomUUID();var vector=new ArrayList<Double>(Collections.nCopies(384,0.0));vector.set(0,1.0);
            when(db.queryForList(anyString(),eq(id))).thenThrow(new InputRejected(404,"reached_snapshot_lookup"));
            var query=new AppearanceSearch.Query(id,"visual",profileSha,"b".repeat(64),List.of(1.0,2.0,40.0,50.0),100,80,"vehicle",List.of("one"),null,null,vector,semantics);
            var reached=assertThrows(InputRejected.class,()->service.create(query,"operator",1));assertEquals("reached_snapshot_lookup",reached.code);
        }
    }
}
