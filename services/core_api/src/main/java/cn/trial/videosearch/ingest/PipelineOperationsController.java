package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
public class PipelineOperationsController {
    private final PipelineOperations operations;
    private final TargetPipeline targets;
    private final byte[] expected;
    public PipelineOperationsController(PipelineOperations operations,TargetPipeline targets,@Value("${VIDEO_WORKER_TOKEN:}") String token) {
        if(token.isBlank()) throw new IllegalArgumentException("worker token must be configured");
        this.operations=operations;this.targets=targets;this.expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,Supplier<Map<String,Object>> action) {
        if(auth==null || auth.length()>1024 || !MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8)))
            return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->ResponseEntity.ok(action.get())).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(PipelineOperations.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
                .onErrorResume(CaptureEvidence.Fault.class,e->Mono.just(ResponseEntity.status(409).body(Map.of("code",e.code()))))
                .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_request"))))
                .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","operations_unavailable"))));
    }
    @GetMapping("/internal/v2/operations/health")
    public Mono<ResponseEntity<Map<String,Object>>> health(@RequestHeader(value="Authorization",required=false) String auth) {
        return call(auth,operations::health);
    }
    @GetMapping("/internal/v2/analysis/jobs")
    public Mono<ResponseEntity<Map<String,Object>>> jobs(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="failed") String state,@RequestParam(required=false) String camera,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) UUID after) {
        return call(auth,()->operations.jobs(state,camera,limit,after));
    }
    @GetMapping("/internal/v2/captures/quarantined")
    public Mono<ResponseEntity<Map<String,Object>>> quarantined(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) UUID after) {
        return call(auth,()->operations.quarantined(limit,after));
    }
    @PostMapping("/internal/v2/analysis/{jobId}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID jobId,@RequestBody PipelineOperations.Recovery request) {
        return call(auth,()->operations.retry(jobId,request));
    }
    @PostMapping("/internal/v2/captures/{frameId}/restore")
    public Mono<ResponseEntity<Map<String,Object>>> restore(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable UUID frameId,@RequestBody PipelineOperations.Recovery request) {
        return call(auth,()->operations.restore(frameId,request));
    }
    @PostMapping("/internal/v2/operations/dispatch")
    public Mono<ResponseEntity<Map<String,Object>>> dispatch(@RequestHeader(value="Authorization",required=false) String auth) {
        return call(auth,()->Map.of("admitted",targets.dispatchWaiting()));
    }
}
