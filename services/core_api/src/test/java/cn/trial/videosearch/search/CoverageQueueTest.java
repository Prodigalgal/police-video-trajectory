package cn.trial.videosearch.search;

import cn.trial.videosearch.ingest.AnalysisQueue;
import cn.trial.videosearch.ingest.AnalysisQueuePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverageQueueTest {
    @Test void pausedSourcesStayVisibleEvenIfAnOldLeaseHasNotExpired() {
        assertEquals("paused",CoverageController.sourceState(false,true));
        assertEquals("paused",CoverageController.sourceState(false,false));
        assertEquals("waiting_for_worker",CoverageController.sourceState(true,false));
        assertEquals("leased",CoverageController.sourceState(true,true));
    }

    @Test @SuppressWarnings("unchecked") void configuredPausedSourceKeepsHistoricalCounts() {
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);
        when(search.authorized("allowed")).thenReturn(true);when(search.mayReadCamera("recording-01")).thenReturn(true);
        var source=new java.util.LinkedHashMap<String,Object>(Map.of("camera_id","recording-01","display_name","1号点位","source_type","rtsp", "source_time_origin","simulated", "enabled",false,"task_active",true));
        for(String field:List.of("sampled","analyzed","pending","failed")) source.put(field,0L);
        when(db.queryForList(anyString())).thenReturn(List.of(source),List.of());
        var response=new CoverageController(db,search,new AnalysisQueue(db, 1000, new AnalysisQueuePolicy(2,5,5,300))).coverage("allowed").block().getBody();
        var sources=(List<Map<String,Object>>)response.get("sources");assertEquals(1,sources.size());
        assertEquals("paused",sources.getFirst().get("processing_state"));assertEquals(false,sources.getFirst().get("enabled"));
        assertEquals("paused",((Map<?,?>)response.get("pipeline")).get("capture_state"));
    }
    @Test
    void reportsCapacityAndWaitingTimeSeparately() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        assertEquals("normal", CoverageController.queueLevel(799, 1000, now.minusSeconds(899), now));
        assertEquals("high", CoverageController.queueLevel(800, 1000, now, now));
        assertEquals("high", CoverageController.queueLevel(1, 1000, now.minusSeconds(900), now));
        assertEquals("full", CoverageController.queueLevel(1000, 1000, now, now));
        assertEquals("normal", CoverageController.queueLevel(0, 1000, null, now));
    }

    @Test
    void rejectsInvalidQueueLimitsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisQueue(null, 0, new AnalysisQueuePolicy(2,5,5,300)));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisQueue(null, 10001, new AnalysisQueuePolicy(2,5,5,300)));
        assertEquals(1000, new AnalysisQueue(null, 1000, new AnalysisQueuePolicy(2,5,5,300)).pendingLimit());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hidesOtherCamerasAndGlobalCapacityFromPartialScope() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        SearchOrchestrator search = mock(SearchOrchestrator.class);
        when(search.authorized("allowed")).thenReturn(true);
        when(search.mayReadCamera("visible")).thenReturn(true);
        Timestamp visible = Timestamp.from(Instant.parse("2026-10-31T23:00:00Z"));
        Timestamp hidden = Timestamp.from(Instant.parse("2026-10-31T22:00:00Z"));
        when(db.queryForList(anyString())).thenReturn(List.of(), List.of(
                Map.of("camera_id", "visible", "pending", 2L, "oldest", visible),
                Map.of("camera_id", "hidden", "pending", 9L, "oldest", hidden)));
        var response = new CoverageController(db, search, new AnalysisQueue(db, 1000, new AnalysisQueuePolicy(2,5,5,300)))
                .coverage("allowed").block(Duration.ofSeconds(10));
        var queue = (Map<String, Object>) response.getBody().get("queue");
        assertEquals(2L, queue.get("scope_pending"));
        assertEquals(visible.toInstant().toString(), queue.get("oldest_pending_at"));
        assertFalse(queue.containsKey("capacity"));
        assertFalse(queue.containsKey("level"));
    }
}
