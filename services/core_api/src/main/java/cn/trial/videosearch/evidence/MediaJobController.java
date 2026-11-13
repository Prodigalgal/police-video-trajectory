package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
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
public class MediaJobController {
    private final MediaJobs jobs;private final SearchOrchestrator search;private final byte[] workerToken;
    public MediaJobController(MediaJobs jobs,SearchOrchestrator search,@Value("${VIDEO_WORKER_TOKEN:}")String token){this.jobs=jobs;this.search=search;workerToken=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    public record Claim(String workerId,int leaseSeconds,List<String> inputKinds){}
    public record Renewal(String workerId,long generation,int leaseSeconds){}
    public record Failure(String workerId,long generation,String errorCode){}
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,boolean worker,Supplier<Map<String,Object>> action){
        boolean authorized=worker?workerToken!=null&&auth!=null&&auth.length()<=1024&&MessageDigest.isEqual(workerToken,auth.getBytes(StandardCharsets.UTF_8)):search.authorized(auth);
        if(!authorized)return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get())).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_media_job_request"))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","media_jobs_unavailable"))));
    }
    @PostMapping("/api/v1/media-jobs")
    public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody MediaJobs.Create body){return call(auth,false,()->jobs.create(body));}
    @GetMapping("/api/v1/media-jobs")
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestParam(name="camera_id")String camera,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){return call(auth,false,()->jobs.list(camera,limit,after));}
    @GetMapping("/api/v1/media-jobs/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id){return call(auth,false,()->jobs.read(id));}
    @PostMapping("/api/v1/media-jobs/{id}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody MediaJobs.Retry body){return call(auth,false,()->jobs.retry(id,body));}
    @PostMapping("/internal/v1/media-jobs/claim")
    public Mono<ResponseEntity<Map<String,Object>>> claim(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Claim body){return call(auth,true,()->(body.inputKinds()==null?jobs.claim(body.workerId(),body.leaseSeconds()):jobs.claim(body.workerId(),body.leaseSeconds(),body.inputKinds())).<Map<String,Object>>map(job->Map.of("job",job)).orElseGet(()->Map.of("job_available",false)));}
    @PostMapping("/internal/v1/media-jobs/{id}/renew")
    public Mono<ResponseEntity<Map<String,Object>>> renew(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody Renewal body){return call(auth,true,()->{if(!jobs.renew(id,body.workerId(),body.generation(),body.leaseSeconds()))throw new InputRejected(409,"media_lease_expired");return Map.of("renewed",true);});}
    @PostMapping("/internal/v1/media-jobs/{id}/fail")
    public Mono<ResponseEntity<Map<String,Object>>> fail(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody Failure body){return call(auth,true,()->{if(!jobs.fail(id,body.workerId(),body.generation(),body.errorCode()))throw new InputRejected(409,"media_lease_expired");return Map.of("recorded",true);});}
    @PostMapping("/internal/v1/media-jobs/complete")
    public Mono<ResponseEntity<Map<String,Object>>> complete(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody MediaJobs.Complete body){return call(auth,true,()->jobs.complete(body));}
}
