package cn.trial.videosearch.ingest;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CaptureAdmissionTest {
    @Test void heldCapturesAreExcludedFromBackgroundAdmission(){
        var db=mock(JdbcTemplate.class);var queue=mock(AnalysisQueue.class);when(queue.pendingLimit()).thenReturn(8);
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        var pipeline=new TargetPipeline(db,queue,null,20,"/unused",null);
        assertEquals(0,pipeline.dispatchWaiting());
        var sql=ArgumentCaptor.forClass(String.class);verify(db).queryForList(sql.capture(),eq(8));
        assertTrue(sql.getValue().contains("c.admission_requested"));verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void validReceiptExplicitlyReleasesAHoldEvenWhenTheQueueIsFull(){
        var db=mock(JdbcTemplate.class);var queue=mock(AnalysisQueue.class);UUID frame=UUID.randomUUID(),receipt=UUID.randomUUID();String sha="a".repeat(64);
        var row=new HashMap<String,Object>();row.put("enqueued_at",null);
        when(db.queryForList(startsWith("SELECT * FROM capture_outbox"),eq(frame),eq(receipt),eq(sha))).thenReturn(List.of(row));
        when(db.queryForObject(anyString(),eq(Integer.class))).thenReturn(8);when(queue.pendingLimit()).thenReturn(8);
        var pipeline=new TargetPipeline(db,queue,null,20,"/unused",null);
        assertTrue(pipeline.activate(new TargetPipeline.Activation(frame,receipt,sha)).isEmpty());
        verify(db).update("UPDATE capture_outbox SET admission_requested=true WHERE frame_id=?",frame);
    }
    @Test void invalidReceiptCannotReleaseAHold(){
        var db=mock(JdbcTemplate.class);var pipeline=new TargetPipeline(db,mock(AnalysisQueue.class),null,20,"/unused",null);
        assertThrows(IllegalStateException.class,()->pipeline.activate(new TargetPipeline.Activation(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64))));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
