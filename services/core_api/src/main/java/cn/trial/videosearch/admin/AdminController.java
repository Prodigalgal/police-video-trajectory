package cn.trial.videosearch.admin;

import cn.trial.videosearch.ingest.PipelineOperations;
import cn.trial.videosearch.ingest.ModuleQueue;
import cn.trial.videosearch.ingest.ModuleResultSource;
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
public class AdminController {
    private final AdminService service;
    private final UserService users;
    private final byte[] expected;
    public AdminController(AdminService service,UserService users,@Value("${VIDEO_ADMIN_TOKEN:}") String token) {
        this.service=service;this.users=users;this.expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,String actor,Supplier<Map<String,Object>> action) {
        if(expected==null) return Mono.just(ResponseEntity.status(503).body(Map.of("code","admin_unavailable")));
        if(auth==null || auth.length()>1024 || !MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8)) || actor==null || !actor.matches("[A-Za-z0-9_.@-]{1,100}"))
            return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->{users.requireAdmin(actor);return ResponseEntity.ok(action.get());}).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
                .onErrorResume(PipelineOperations.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
                .onErrorResume(ModuleQueue.RecoveryRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
                .onErrorResume(ModuleQueue.CapacityExceeded.class,e->Mono.just(ResponseEntity.status(429).body(Map.of("code","module_queue_full"))))
                .onErrorResume(ModuleResultSource.Invalid.class,e->Mono.just(ResponseEntity.status(422).body(Map.of("code","invalid_module_source"))))
                .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_admin_request"))))
                .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","admin_unavailable"))));
    }
    @GetMapping("/internal/admin/overview")
    public Mono<ResponseEntity<Map<String,Object>>> overview(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor) { return call(auth,actor,service::overview); }
    @GetMapping("/internal/admin/cameras")
    public Mono<ResponseEntity<Map<String,Object>>> cameras(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor) { return call(auth,actor,service::cameras); }
    @PutMapping("/internal/admin/cameras/{camera}")
    public Mono<ResponseEntity<Map<String,Object>>> update(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor,@PathVariable String camera,@RequestBody AdminService.CameraUpdate body) { return call(auth,actor,()->service.updateCamera(camera,body,actor)); }
    @GetMapping("/internal/admin/jobs")
    public Mono<ResponseEntity<Map<String,Object>>> jobs(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor,
            @RequestParam(defaultValue="failed") String state,@RequestParam(required=false) String camera,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) UUID after) { return call(auth,actor,()->service.jobs(state,camera,limit,after)); }
    @PostMapping("/internal/admin/jobs/{job}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor,@PathVariable UUID job,@RequestBody AdminService.Retry body) { return call(auth,actor,()->service.retry(job,body,actor)); }
    @PostMapping("/internal/admin/processing/pause")
    public Mono<ResponseEntity<Map<String,Object>>> pause(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor) { return call(auth,actor,()->service.pause(actor)); }

    @GetMapping("/internal/admin/module-jobs")
    public Mono<ResponseEntity<Map<String,Object>>> moduleJobs(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor,
            @RequestParam(defaultValue="failed") String state,@RequestParam(required=false) String camera,
            @RequestParam(name="module_key",required=false) String kind,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) UUID after){
        return call(auth,actor,()->service.moduleJobs(state,camera,kind,limit,after));
    }
    @PostMapping("/internal/admin/module-jobs/{job}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retryModule(@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="X-Actor-Id",required=false) String actor,@PathVariable UUID job,@RequestBody AdminService.Retry body){
        return call(auth,actor,()->service.retryModule(job,body,actor));
    }
}
