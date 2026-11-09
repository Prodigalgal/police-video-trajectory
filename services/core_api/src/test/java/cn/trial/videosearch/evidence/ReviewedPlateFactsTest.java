package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchCursorCodec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewedPlateFactsTest {
    @Test void oldPitCannotReturnRevokedRevisedOrReplacedEvidence(){
        var db=mock(JdbcTemplate.class);var facts=new ReviewedPlateFacts(db,true);UUID observation=UUID.randomUUID(),frame=UUID.randomUUID();
        var row=new HashMap<String,Object>(Map.of("observation_id",observation,"frame_id",frame,"camera_id","camera-1","record_version",1L,"source_record_version",1L,"target_type","vehicle","deleted",false));
        row.putAll(Map.of("projection_outcome","ready","requested_revision",2L,"published_revision",2L,"payload_sha256","source","evidence_sha256","source","fact_sha256","fact","analysis_state","completed"));
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
        var hit=Map.<String,Object>of("observation_id",observation.toString(),"frame_id",frame.toString(),"camera_id","camera-1","record_version",1,"plate_projection_revision",2,"plate_fact_sha256","fact","reviewed_plate_number","闽E12345");
        assertDoesNotThrow(()->facts.validateHits(List.of(hit),"闽E12345"));
        for(var change:Map.<String,Object>of("requested_revision",3L,"record_version",2L,"payload_sha256","changed","projection_outcome","empty","deleted",true).entrySet()){
            Object prior=row.put(change.getKey(),change.getValue());
            assertEquals("plate_search_changed",assertThrows(SearchCursorCodec.Invalid.class,()->facts.validateHits(List.of(hit),"闽E12345")).code);
            row.put(change.getKey(),prior);
        }
        assertThrows(SearchCursorCodec.Invalid.class,()->facts.validateHits(List.of(hit),"闽E54321"));
    }
    @Test void disabledDetailsDoNotReadAndMissingSourceCannotPublishPlate(){
        var db=mock(JdbcTemplate.class);assertEquals("disabled",new ReviewedPlateFacts(db,false).details(UUID.randomUUID(),1).get("state"));verifyNoInteractions(db);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertEquals("source_changed",new ReviewedPlateFacts(db,true).resolve(UUID.randomUUID()).outcome());
    }
}
