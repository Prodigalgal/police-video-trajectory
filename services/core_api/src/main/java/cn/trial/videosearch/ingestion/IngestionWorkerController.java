package cn.trial.videosearch.ingestion;

import cn.trial.videosearch.admin.AdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/internal/v2/ingestion/tasks")
public class IngestionWorkerController {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionWorkerController.class);
    private final IngestionRuntime runtime;private final byte[] expected;
    public IngestionWorkerController(IngestionRuntime runtime,@Value("${VIDEO_WORKER_TOKEN:}")String token){this.runtime=runtime;this.expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,Supplier<Map<String,Object>> action){
        if(expected==null||auth==null||auth.length()>1024||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8)))return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get())).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_ingestion_request"))))
            .onErrorResume(Exception.class,e->{LOG.warn("Ingestion worker operation failed: {}",e.getClass().getSimpleName());return Mono.just(ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("code","ingestion_unavailable")));});
    }
    @PostMapping("/claim") public Mono<ResponseEntity<Map<String,Object>>> claim(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody IngestionRuntime.Claim body){return call(auth,()->runtime.claim(body));}
    @PostMapping("/{id}/renew") public Mono<ResponseEntity<Map<String,Object>>> renew(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody IngestionRuntime.Lease body){return call(auth,()->runtime.renew(id,body));}
    @PostMapping("/{id}/finish") public Mono<ResponseEntity<Map<String,Object>>> finish(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody IngestionRuntime.Finish body){return call(auth,()->runtime.finish(id,body));}
    @PostMapping("/{id}/progress") public Mono<ResponseEntity<Map<String,Object>>> progress(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody IngestionRuntime.Progress body){return call(auth,()->runtime.progress(id,body));}
    @GetMapping("/{id}/captures") public Mono<ResponseEntity<Map<String,Object>>> captures(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestParam String worker,@RequestParam long generation,@RequestParam(name="input_sha256")String sha){return call(auth,()->runtime.captures(id,worker,generation,sha));}
    @PostMapping("/{id}/receipts") public Mono<ResponseEntity<Map<String,Object>>> receipts(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody IngestionRuntime.ReceiptLookup body){return call(auth,()->runtime.receipts(id,body));}
}
