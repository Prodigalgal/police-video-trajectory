package cn.trial.videosearch.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/internal/v1/appearance")
public class AppearanceWorkerController {
    private static final Logger LOG=LoggerFactory.getLogger(AppearanceWorkerController.class);
    private final AppearanceJobs jobs;private final byte[] token;
    public AppearanceWorkerController(AppearanceJobs jobs,@Value("${VIDEO_WORKER_TOKEN:}")String token){this.jobs=jobs;this.token=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    private boolean allowed(String auth){return token!=null&&auth!=null&&auth.length()<=1024&&MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8));}
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,Supplier<Map<String,Object>> action){
        return Mono.fromCallable(()->{if(!allowed(auth))throw new InputRejected(401,"unauthorized");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get());}).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,error->Mono.just(ResponseEntity.status(error.status).body(Map.of("code",error.code))))
            .onErrorResume(IllegalArgumentException.class,error->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_appearance_request"))))
            .onErrorResume(Exception.class,error->{LOG.warn("Appearance worker request failed",error);return Mono.just(ResponseEntity.status(503).body(Map.of("code","appearance_unavailable")));});
    }
    public record Claim(String workerId,int leaseSeconds,List<String> profiles){}
    public record Renew(String workerId,long generation,int leaseSeconds){}
    public record Fail(String workerId,long generation,String errorCode){}
    @PostMapping("/claim")public Mono<ResponseEntity<Map<String,Object>>> claim(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Claim body){return call(auth,()->jobs.claim(body.workerId(),body.leaseSeconds(),body.profiles()));}
    @PostMapping("/{id}/renew")public Mono<ResponseEntity<Map<String,Object>>> renew(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody Renew body){return call(auth,()->jobs.renew(id,body.workerId(),body.generation(),body.leaseSeconds()));}
    @PostMapping("/{id}/fail")public Mono<ResponseEntity<Map<String,Object>>> fail(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody Fail body){return call(auth,()->jobs.fail(id,body.workerId(),body.generation(),body.errorCode()));}
    @PostMapping("/complete")public Mono<ResponseEntity<Map<String,Object>>> complete(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody AppearanceJobs.Complete body){return call(auth,()->jobs.complete(body));}
    @GetMapping("/{id}/image")public Mono<ResponseEntity<byte[]>> image(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestParam String workerId,@RequestParam long generation){
        return Mono.fromCallable(()->{if(!allowed(auth))throw new InputRejected(401,"unauthorized");var image=jobs.image(id,workerId,generation);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).header("X-Content-SHA256",image.sha256()).body(image.bytes());}).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,error->Mono.just(ResponseEntity.status(error.status).body(new byte[0])))
            .onErrorResume(Exception.class,error->{LOG.warn("Appearance image read failed",error);return Mono.just(ResponseEntity.status(503).body(new byte[0]));});
    }
}
