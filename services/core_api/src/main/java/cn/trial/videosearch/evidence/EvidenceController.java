package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/** Read a bounded key frame only after authenticating and checking camera scope. */
@RestController
public class EvidenceController {
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private final EvidenceFiles files;

    public EvidenceController(JdbcTemplate db, SearchOrchestrator search, EvidenceFiles files) {
        this.db = db;
        this.search = search;
        this.files = files;
    }

    @GetMapping("/api/v1/frames/{frameId}/image")
    public Mono<ResponseEntity<byte[]>> image(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @PathVariable UUID frameId) {
        if (!search.authorized(auth)) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        return Mono.fromCallable(() -> read(frameId)).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(Exception.class, error -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()));
    }

    private ResponseEntity<byte[]> read(UUID frameId) throws Exception {
        UUID requestId = UUID.randomUUID();
        String result = "not_found";
        try {
            var rows = db.queryForList("""
                    SELECT f.evidence_ref,s.camera_id FROM frames f JOIN processing_tasks t ON t.task_id=f.task_id
                    JOIN sources s ON s.source_id=t.source_id WHERE f.frame_id=? AND f.analysis_state='completed'
                    """, frameId);
            if (rows.size() != 1 || !search.mayReadCamera((String) rows.getFirst().get("camera_id"))) return ResponseEntity.notFound().build();
            var frame = files.read(frameId, (String)rows.getFirst().get("evidence_ref"));
            if (frame == null) return ResponseEntity.notFound().build();
            result = "ok";
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(frame.bytes());
        } finally {
            db.update("INSERT INTO audit_events(actor_id, action, result, request_id) VALUES (?, ?, ?, ?)",
                    "trial-service-account", "read_evidence", result, requestId);
        }
    }
}
