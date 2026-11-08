package cn.trial.videosearch.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisQueuePolicyTest {
    @Test void rejectsUnsafePolicyAtStartup() {
        assertThrows(IllegalArgumentException.class,()->new AnalysisQueuePolicy(0,5,5,300));
        assertThrows(IllegalArgumentException.class,()->new AnalysisQueuePolicy(2,0,5,300));
        assertThrows(IllegalArgumentException.class,()->new AnalysisQueuePolicy(2,21,5,300));
        assertThrows(IllegalArgumentException.class,()->new AnalysisQueuePolicy(2,5,10,5));
        assertThrows(IllegalArgumentException.class,()->new AnalysisQueuePolicy(2,5,5,3601));
    }
    @Test void capsRetryDelayWithoutIntegerOverflowAndRespectsQueueCapacity() {
        var policy=new AnalysisQueuePolicy(4,20,5,300);
        assertEquals(300,policy.retryDelaySeconds(Integer.MAX_VALUE));
        assertEquals(2,policy.snapshot(2).get("active_limit"));
        assertThrows(IllegalArgumentException.class,()->policy.retryDelaySeconds(0));
    }
    @Test void pausedClaimsAndInvalidFailuresNeverMutateJobs() {
        var db=mock(JdbcTemplate.class);var queue=new AnalysisQueue(db,10,new AnalysisQueuePolicy(2,5,5,300));
        when(db.queryForObject(anyString(),eq(Boolean.class))).thenReturn(false);
        assertTrue(queue.claimTargets("worker-1",120).isEmpty());
        assertThrows(IllegalArgumentException.class,()->queue.fail(java.util.UUID.randomUUID(),"worker-1",1,null));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
