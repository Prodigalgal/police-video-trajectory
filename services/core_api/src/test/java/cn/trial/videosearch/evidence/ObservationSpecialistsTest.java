package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ObservationSpecialistsTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final TemporalEvidence evidence=mock(TemporalEvidence.class);
    final ObservationSpecialists query=new ObservationSpecialists(db,evidence,true);
    final UUID observation=UUID.randomUUID(),frame=UUID.randomUUID();

    void source(){
        var anchor=new TemporalEvidence.Anchor(observation,3L,Map.of(),new TemporalEvidence.Frame(Map.of("frame_id",frame,"camera_id","recording-01")));
        when(evidence.anchor(observation,3L)).thenReturn(anchor);
        when(db.queryForObject(anyString(),eq(Boolean.class))).thenReturn(false);
    }
    Map<String,Object> row(int ordinal,long sourceVersion){
        var row=new LinkedHashMap<String,Object>();
        row.putAll(Map.of("job_id",new UUID(0,ordinal),"input_id",UUID.randomUUID(),"frame_id",frame,"camera_id","recording-01",
            "anchor_observation_id",observation,"anchor_frame_id",frame,"anchor_record_version",sourceVersion,"current_version",3L,
            "input_manifest_sha256","a".repeat(64),"saved_manifest_sha256","a".repeat(64)));
        row.putAll(Map.of("purpose","single_frame","input_created_by","reader","input_created_at",Timestamp.from(Instant.parse("2026-11-08T01:00:00Z")),
            "operation","license_plate","profile","frozen-profile","input_sha256","b".repeat(64),"state","queued","attempts",0,"revision",1L));
        row.putAll(Map.of("created_by","reader","created_at",Timestamp.from(Instant.parse("2026-11-08T02:00:00Z")),
            "updated_at",Timestamp.from(Instant.parse("2026-11-08T02:00:00Z")),"next_attempt_at",Timestamp.from(Instant.parse("2026-11-08T02:00:00Z"))));
        return row;
    }

    @Test void disabledInvalidAndUnauthorizedSourcesNeverReadJobs(){
        assertEquals("specialist_disabled",assertThrows(InputRejected.class,()->new ObservationSpecialists(db,evidence,false).list(observation,3,10,null)).code);
        assertThrows(IllegalArgumentException.class,()->query.list(observation,0,10,null));
        assertThrows(IllegalArgumentException.class,()->query.list(observation,3,21,null));verifyNoInteractions(db,evidence);
        when(evidence.anchor(observation,3L)).thenThrow(new InputRejected(404,"observation_unavailable"));
        assertThrows(InputRejected.class,()->query.list(observation,3,10,null));verifyNoInteractions(db);
    }
    @Test void projectedPageIsBoundedKeepsStaleSourcesAndDoesNotReadImagesOrResults(){
        source();var rows=List.of(row(4,3),row(3,2),row(2,3));
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(rows);
        var result=query.list(observation,3,2,null);assertEquals(true,result.get("has_more"));assertEquals(new UUID(0,3).toString(),result.get("next_cursor"));
        var entries=(List<?>)result.get("items");assertEquals(2,entries.size());
        var stale=(Map<?,?>)((Map<?,?>)entries.get(1)).get("input");assertEquals("stale",stale.get("source_status"));
        assertEquals("paused",((Map<?,?>)((Map<?,?>)entries.getFirst()).get("job")).get("dispatch_state"));
        verify(evidence,times(2)).anchor(observation,3L);
        verify(db).queryForList(argThat(sql->sql.contains("j.created_at DESC,j.job_id DESC")&&!sql.contains("input_descriptor")&&!sql.contains("specialist_analysis_results")),eq(observation),eq(frame),eq("recording-01"),eq(3));
    }
    @Test void foreignCursorIsRejectedBeforeAnyPageIsRead(){
        source();when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertEquals("invalid_specialist_cursor",assertThrows(InputRejected.class,()->query.list(observation,3,10,UUID.randomUUID())).code);
        verify(db,times(1)).queryForList(anyString(),any(Object[].class));
    }
    @Test void tiedTimesUseScopedCursorTimestampAndUuidInsteadOfOffset(){
        source();UUID after=new UUID(0,9);Timestamp clock=Timestamp.from(Instant.parse("2026-11-08T02:00:00.000001Z"));
        when(db.queryForList(startsWith("SELECT j.created_at "),any(Object[].class))).thenReturn(List.of(Map.of("created_at",clock)));
        when(db.queryForList(startsWith("SELECT j.job_id,"),any(Object[].class))).thenReturn(List.of(row(4,3)));
        var result=query.list(observation,3,10,after);assertEquals(false,result.get("has_more"));assertNull(result.get("next_cursor"));
        verify(db).queryForList(contains("(j.created_at,j.job_id)<(?,?)"),eq(observation),eq(frame),eq("recording-01"),eq(clock),eq(after),eq(11));
    }
    @Test void conflictingInputBindingIsNotPresentedAsAnEmptyOrSuccessfulPage(){
        source();var bad=row(1,3);bad.put("saved_manifest_sha256","c".repeat(64));
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(bad));
        assertEquals("specialist_input_changed",assertThrows(InputRejected.class,()->query.list(observation,3,10,null)).code);
    }
    @Test void sourceRevisionIsRecheckedEvenWhenThePageIsEmpty(){
        source();when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        var first=evidence.anchor(observation,3L);when(evidence.anchor(observation,3L)).thenReturn(first).thenThrow(new InputRejected(409,"observation_revision_conflict"));
        assertEquals("observation_revision_conflict",assertThrows(InputRejected.class,()->query.list(observation,3,10,null)).code);
    }
}
