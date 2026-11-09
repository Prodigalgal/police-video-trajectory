package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** Serve registered historical files or verified same-session recordings. */
@RestController
public class ArchiveController {
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private final Path root;
    private final FrameRecordings recordings;

    public ArchiveController(JdbcTemplate db, SearchOrchestrator search, FrameRecordings recordings, @Value("${VIDEO_ARCHIVE_DIR:/recordings}") String root) {
        this.db = db;
        this.search = search;
        this.recordings = recordings;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @GetMapping("/api/v1/frames/{frameId}/archive")
    public Mono<ResponseEntity<Flux<DataBuffer>>> archive(@RequestHeader(value = "Authorization", required = false) String auth,
                                                           @RequestHeader(value = "Range", required = false) String range,
                                                           @PathVariable UUID frameId) {
        if (!search.authorized(auth)) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        return Mono.fromCallable(() -> read(frameId, range)).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(InputRejected.class,error->Mono.just(ResponseEntity.status(error.status).build()))
                .onErrorResume(Exception.class, error -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()));
    }

    private ResponseEntity<Flux<DataBuffer>> read(UUID frameId, String requested) throws Exception {
        UUID requestId = UUID.randomUUID();
        String result = "not_found";
        try {
            var rows = db.queryForList("""
                    SELECT s.camera_id,s.source_type,s.source_ref FROM frames f JOIN processing_tasks t ON t.task_id=f.task_id
                    JOIN sources s ON s.source_id=t.source_id WHERE f.frame_id=? AND f.analysis_state='completed'
                    """, frameId);
            if (rows.size() != 1 || !search.mayReadCamera((String) rows.getFirst().get("camera_id"))) return ResponseEntity.notFound().build();
            Path path;
            if("rtsp".equals(rows.getFirst().get("source_type"))){
                var segment=recordings.playback(frameId,true);
                if(segment.isEmpty())return ResponseEntity.notFound().build();
                path=segment.get().path();
            }else{
            if(!"archive".equals(rows.getFirst().get("source_type")))return ResponseEntity.notFound().build();
            String ref = (String) rows.getFirst().get("source_ref");
            if (ref == null || !ref.matches("archive:recording-0[1-6]")) return ResponseEntity.notFound().build();
            path = root.resolve(ref.substring("archive:".length()) + ".mp4").normalize();
            if (!path.getParent().equals(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return ResponseEntity.notFound().build();
            }
            var response=MediaRangeResponse.read(path,requested);
            result=response.getStatusCode().is2xxSuccessful()?"ok":"range_rejected";
            return response;
        } finally {
            db.update("INSERT INTO audit_events(actor_id, action, result, request_id) VALUES (?, ?, ?, ?)",
                    "trial-service-account", "read_archive", result, requestId);
        }
    }

    public static long[] parseRange(String header,long size) { return MediaRangeResponse.range(header,size); }
}
