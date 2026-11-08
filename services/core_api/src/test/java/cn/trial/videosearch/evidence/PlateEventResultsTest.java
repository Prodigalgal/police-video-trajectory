package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlateEventResultsTest {
    private final UUID event=UUID.randomUUID(),run=UUID.randomUUID();
    private Map<String,Object> summary(){return Map.of("event_id",event.toString(),"intent","collect_vehicle_passages","scene_run_id",run.toString(),"media_epoch",0,"relative_media_ms",1000,"decision","selected","business_state","need_read");}
    private JdbcTemplate database(int count,int pending,int recognized){
        var db=mock(JdbcTemplate.class);
        when(db.queryForList(contains("FROM specialist_plate_reviews"),any(UUID.class),anyString(),any(UUID.class),any(),any(),any())).thenReturn(List.of());
        when(db.queryForList(contains("AS admissions"),anyString(),anyString())).thenReturn(List.of(Map.of("admissions",count,"pending",pending,"recognized",recognized)));
        return db;
    }
    @Test void pendingAutomaticWorkDoesNotDuplicateButExplicitVerificationRemainsBounded(){
        var ledger=new PlateEventResults(database(1,1,0),3);var descriptor=Map.<String,Object>of("camera_id","recording-01");
        assertEquals("waiting_result",ledger.assess(summary(),descriptor).get("business_state"));
        var explicit=new LinkedHashMap<>(summary());explicit.put("intent","verify_vehicle");
        assertEquals("need_read",ledger.assess(explicit,descriptor).get("business_state"));
        assertEquals("unreadable",new PlateEventResults(database(3,0,0),3).assess(explicit,descriptor).get("business_state"));
    }
    @Test void unreviewedReadingRemainsPendingReviewEvenWhenSubmissionLimitIsReached(){
        assertEquals("pending_review",new PlateEventResults(database(3,0,1),3).assess(summary(),Map.of("camera_id","recording-01")).get("business_state"));
    }
    @Test void onlyConfirmedReferenceCanSuppressANewAutomaticRead(){
        var db=database(1,0,1);var reference=Map.<String,Object>of("job_id",UUID.randomUUID(),"frame_id",UUID.randomUUID(),"revision",1,"input_id",UUID.randomUUID(),"input_manifest_sha256","a".repeat(64),"profile","plate-a");
        when(db.queryForList(contains("FROM specialist_plate_reviews"),any(UUID.class),anyString(),any(UUID.class),any(),any(),any())).thenReturn(List.of(reference));
        var result=new PlateEventResults(db,3).assess(summary(),Map.of("camera_id","recording-01"));
        assertEquals("reused",result.get("decision"));assertEquals("reuse_result",result.get("business_state"));
        assertFalse(((Map<?,?>)result.get("reuse_reference")).containsKey("plate_text"));
    }
    @Test void missingEventDoesNotInventHistory(){
        var db=mock(JdbcTemplate.class);var value=Map.<String,Object>of("decision","deferred");
        assertSame(value,new PlateEventResults(db,3).assess(value,Map.of()));verifyNoInteractions(db);
    }
    @Test void nullDecisionIsAValidationError(){
        var ledger=new PlateEventResults(mock(JdbcTemplate.class),3);
        var body=new PlateEventResults.Review(UUID.randomUUID(),"a".repeat(64),0,null,0,true);
        assertEquals("invalid_plate_review",assertThrows(InputRejected.class,()->ledger.review(UUID.randomUUID(),Map.of(),Map.of(),body,"reader")).code);
    }
}
