package cn.trial.videosearch.evidence;

import cn.trial.videosearch.http.SecureJsonClient;
import cn.trial.videosearch.index.ObservationIndexReader;
import cn.trial.videosearch.index.ReviewedPlateDocument;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Nonvisual projection protocol contracts; not plate recognition accuracy evidence. */
class ReviewedPlateSearchStatusTest {
    private final UUID observation=UUID.randomUUID(),frame=UUID.randomUUID();
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ReviewedPlateFacts facts=mock(ReviewedPlateFacts.class);
    private final ObservationIndexReader index=mock(ObservationIndexReader.class);
    private final SearchOrchestrator scope=mock(SearchOrchestrator.class);
    private final ReviewedPlateSearchStatus status=new ReviewedPlateSearchStatus(db,facts,index,scope);
    private final Map<String,Object> task=new LinkedHashMap<>();
    private ReviewedPlateFacts.Snapshot snapshot(String outcome){
        return new ReviewedPlateFacts.Snapshot(observation,1,frame,"camera-1","a".repeat(64),outcome,"ready".equals(outcome)?"闽E12345":null,
            "ready".equals(outcome)?List.of(Map.of("job_id",UUID.nameUUIDFromBytes(new byte[]{1}).toString(),"review_revision",1,"result_sha256","b".repeat(64),"input_manifest_sha256","c".repeat(64),"region_index",0,"review_scope","single_frame")):List.of(),outcome+"-fact");
    }
    private Map<String,Object> base(){return Map.of("observation_id",observation.toString(),"record_version",1,"frame_id",frame.toString(),"camera_id","camera-1","target_type","vehicle","deleted",false,"description","非视觉协议字段");}
    private void ready(){
        when(scope.mayReadCamera("camera-1")).thenReturn(true);when(facts.enabled()).thenReturn(true);when(facts.resolve(observation)).thenReturn(snapshot("ready"));
        task.putAll(Map.of("requested_revision",3L,"published_revision",3L,"source_record_version",1L,"evidence_sha256","a".repeat(64),"fact_sha256","ready-fact","projection_outcome","ready","attempts",0,"document",CanonicalJson.encode(base(),4096).payload()));
        when(db.queryForList(anyString(),eq(1L),eq(observation))).thenAnswer(call->List.of(new LinkedHashMap<>(task)));
        when(index.readReviewedPlates(List.of(observation))).thenAnswer(call->Map.of("docs",List.of(document())));
    }
    private Map<String,Object> document(){return new LinkedHashMap<>(Map.of("_id",observation.toString(),"found",true,"_version",3,"_source",ReviewedPlateDocument.build(snapshot("ready"),3,base())));}
    private Map<String,Object> read(){return status.read(observation,1);}
    @Test void searchableRequiresCurrentFactTaskAndCompleteDocumentAndDoesNotWrite(){
        ready();var response=read();assertEquals("searchable",response.get("state"));assertEquals("车牌 闽E12345",response.get("query"));
        assertEquals(snapshot("ready").proofs(),response.get("proofs"));assertFalse(response.containsKey("document"));assertFalse(response.containsKey("evidence_sha256"));
        verify(index).readReviewedPlates(List.of(observation));verify(db,times(2)).queryForList(anyString(),eq(1L),eq(observation));
        verifyNoMoreInteractions(db);verify(index,never()).read(anyList());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","review_required","review_conflict","source_changed","not_queued","pending","retry_wait","sync_failed","stale"})
    void nonReadyStatesNeverReadEsOrOfferASearch(String expected){
        ready();
        switch(expected){
            case "disabled"->when(facts.enabled()).thenReturn(false);
            case "review_required"->when(facts.resolve(observation)).thenReturn(snapshot("empty"));
            case "review_conflict"->when(facts.resolve(observation)).thenReturn(snapshot("conflict"));
            case "source_changed"->when(facts.resolve(observation)).thenReturn(snapshot("invalid_source"));
            case "not_queued"->when(db.queryForList(anyString(),eq(1L),eq(observation))).thenReturn(List.of());
            case "pending","retry_wait","sync_failed"->{task.put("requested_revision",4L);if(expected.equals("retry_wait"))task.put("last_error_code","search_backend_unavailable");if(expected.equals("sync_failed"))task.put("attempts",10);}
            default->task.put("fact_sha256","other-fact");
        }
        var result=read();assertEquals(expected,result.get("state"));assertNull(result.get("query"));verifyNoInteractions(index);
    }
    @ParameterizedTest @ValueSource(strings={"missing","version","fractional_version","content","proof","foreign_id","duplicate","item_error","malformed","transport","base_changed"})
    void missingWrongOrUnreadableIndexNeverClaimsReadiness(String scenario){
        ready();var document=document();String expected="stale";
        switch(scenario){
            case "missing"->{document=Map.of("_id",observation.toString(),"found",false);expected="not_indexed";}
            case "version"->document.put("_version",2);
            case "fractional_version"->document.put("_version",3.5);
            case "content","proof"->{var source=new LinkedHashMap<>(ReviewedPlateDocument.build(snapshot("ready"),3,base()));source.put(scenario.equals("proof")?"plate_proofs":"description",scenario.equals("proof")?List.of():"变化的正文");document.put("_source",source);}
            case "foreign_id"->{document.put("_id",UUID.randomUUID().toString());expected="unavailable";}
            case "duplicate"->{expected="unavailable";}
            case "item_error"->{document.put("error",Map.of("type","permission_denied"));expected="unavailable";}
            case "malformed"->{document.remove("found");expected="unavailable";}
            case "transport"->{expected="unavailable";}
            case "base_changed"->task.put("document",CanonicalJson.encode(Map.of("deleted",true),4096).payload());
        }
        when(index.readReviewedPlates(anyList())).thenReturn(Map.of("docs",scenario.equals("duplicate")?List.of(document,document):List.of(document)));
        if(scenario.equals("transport"))when(index.readReviewedPlates(anyList())).thenThrow(mock(SecureJsonClient.UpstreamFailure.class));
        var result=read();assertEquals(expected,result.get("state"));assertNull(result.get("query"));
    }
    @ParameterizedTest @ValueSource(strings={"fact","task"})
    void revocationOrNewProjectionWhileEsIsReadRequiresANewSnapshot(String scenario){
        ready();when(index.readReviewedPlates(anyList())).thenAnswer(call->{if(scenario.equals("fact"))when(facts.resolve(observation)).thenReturn(snapshot("empty"));else task.put("requested_revision",4L);return Map.of("docs",List.of(document()));});
        assertEquals("plate_search_changed",assertThrows(InputRejected.class,this::read).code);
    }
    @Test void revisionScopeAndMissingSourceAreRejectedBeforeIndexAccess(){
        ready();assertEquals(422,assertThrows(InputRejected.class,()->status.read(observation,0)).status);
        assertEquals("observation_revision_conflict",assertThrows(InputRejected.class,()->status.read(observation,2)).code);
        when(scope.mayReadCamera(anyString())).thenReturn(false);assertEquals(404,assertThrows(InputRejected.class,this::read).status);
        when(facts.resolve(observation)).thenReturn(new ReviewedPlateFacts.Snapshot(observation,0,null,"",null,"source_changed",null,List.of(),"missing"));
        assertEquals(404,assertThrows(InputRejected.class,this::read).status);verifyNoInteractions(index);
    }
}
