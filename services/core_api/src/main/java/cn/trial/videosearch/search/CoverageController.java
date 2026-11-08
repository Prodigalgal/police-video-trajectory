package cn.trial.videosearch.search;

import cn.trial.videosearch.ingest.AnalysisQueue;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Trial-scoped counters; sampled frame counts are not a video-coverage claim. */
@RestController
public final class CoverageController {
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private final AnalysisQueue analysis;

    public CoverageController(JdbcTemplate db, SearchOrchestrator search, AnalysisQueue analysis) {
        this.db = db;
        this.search = search;
        this.analysis = analysis;
    }

    @GetMapping("/api/v1/coverage")
    public Mono<ResponseEntity<Map<String, Object>>> coverage(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!search.authorized(auth)) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "未授权访问")));
        return Mono.fromCallable(() -> ResponseEntity.ok(snapshot())).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(Exception.class, error -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", "状态暂不可用"))));
    }

    private Map<String, Object> snapshot() {
        UUID requestId = UUID.randomUUID();
        String outcome = "failed";
        try {
            var rows = db.queryForList("""
                    SELECT s.camera_id,c.display_name,s.source_type,s.source_time_origin,s.enabled,
                           bool_or(t.state='active' AND t.lease_expires_at>clock_timestamp()) AS task_active,
                           count(f.frame_id) AS sampled,
                           count(f.frame_id) FILTER (WHERE f.analysis_state='completed') AS analyzed,
                           count(f.frame_id) FILTER (WHERE f.analysis_state='queued') AS pending,
                           count(f.frame_id) FILTER (WHERE f.analysis_state='failed') AS failed,
                           max(f.received_at) AS latest_received_at
                    FROM sources s JOIN cameras c ON c.camera_id=s.camera_id AND c.enabled
                    LEFT JOIN processing_tasks t ON t.source_id=s.source_id
                    LEFT JOIN frames f ON f.task_id=t.task_id
                    WHERE s.source_type IN ('rtsp','archive')
                    GROUP BY s.source_id,c.display_name ORDER BY s.camera_id,s.source_type,s.source_id
                    """);
            var sources = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                String camera = (String) row.get("camera_id");
                if (!search.mayReadCamera(camera)) continue;
                var item = new LinkedHashMap<String, Object>();
                item.put("camera_id", camera);
                item.put("camera_name",row.get("display_name"));
                item.put("source_type",row.get("source_type"));
                item.put("enabled",Boolean.TRUE.equals(row.get("enabled")));
                item.put("source_time_origin", row.get("source_time_origin"));
                item.put("task_active", Boolean.TRUE.equals(row.get("task_active")));
                item.put("processing_state",sourceState(Boolean.TRUE.equals(row.get("enabled")),Boolean.TRUE.equals(row.get("task_active"))));
                for (String field : new String[]{"sampled", "analyzed", "pending", "failed"})
                    item.put(field, ((Number) row.get(field)).longValue());
                Timestamp timestamp = (Timestamp) row.get("latest_received_at");
                item.put("latest_received_at", timestamp == null ? null : timestamp.toInstant().toString());
                sources.add(item);
            }
            var queues = db.queryForList("""
                    SELECT s.camera_id, count(*) AS pending, min(j.created_at) AS oldest
                    FROM analysis_jobs j JOIN frames f ON f.frame_id=j.frame_id
                    JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id
                    WHERE j.state IN ('queued','active') GROUP BY s.camera_id
                    """);
            long scopePending = 0;
            boolean hiddenQueue = false;
            Timestamp oldest = null;
            for (var row : queues) {
                if (!search.mayReadCamera((String) row.get("camera_id"))) { hiddenQueue = true; continue; }
                scopePending += ((Number) row.get("pending")).longValue();
                Timestamp candidate = (Timestamp) row.get("oldest");
                if (candidate != null && (oldest == null || candidate.before(oldest))) oldest = candidate;
            }
            var queue = new LinkedHashMap<String, Object>();
            queue.put("scope_pending", scopePending);
            queue.put("oldest_pending_at", oldest == null ? null : oldest.toInstant().toString());
            if (!hiddenQueue) {
                queue.put("capacity", analysis.pendingLimit());
                queue.put("level", queueLevel(scopePending, analysis.pendingLimit(),
                        oldest == null ? null : oldest.toInstant(), Instant.now()));
            }
            outcome = "ok";
            boolean anyEnabled=sources.stream().anyMatch(source->Boolean.TRUE.equals(source.get("enabled")));
            var pipeline=new LinkedHashMap<String,Object>();
            pipeline.put("capture_state",sources.isEmpty()?"unconfigured":anyEnabled?"enabled":"paused");
            pipeline.put("analysis_claims_enabled",db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));
            pipeline.put("source_count",sources.size());
            return Map.of("sources", sources, "queue", queue, "coverage", "unknown",
                    "pipeline",pipeline,"observed_at",Instant.now().toString(),
                    "message", !sources.isEmpty() && !anyEnabled ? "采集已暂停，仍可检索已入库线索。以下为各源历史累计数量。" : "以下为历史累计数量；任务已领取不等于视频持续在线，计数不能证明完整覆盖。");
        } finally {
            db.update("INSERT INTO audit_events(actor_id, action, result, request_id) VALUES (?, ?, ?, ?)",
                    "trial-service-account", "read_coverage", outcome, requestId);
        }
    }

    static String sourceState(boolean enabled,boolean leaseActive) {
        return !enabled?"paused":leaseActive?"leased":"waiting_for_worker";
    }

    static String queueLevel(long pending, int capacity, Instant oldest, Instant now) {
        if (pending >= capacity) return "full";
        if (pending * 5 >= (long) capacity * 4 ||
                (oldest != null && !oldest.isAfter(now.minus(Duration.ofMinutes(15))))) return "high";
        return "normal";
    }
}
