package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.UserService;
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
public class SpecialistController {
    private final SpecialistJobs jobs;private final UserService users;private final ObservationSpecialists observations;private final byte[] adminToken;private final byte[] workerToken;
    public SpecialistController(SpecialistJobs jobs,UserService users,ObservationSpecialists observations,@Value("${VIDEO_ADMIN_TOKEN:}")String admin,@Value("${VIDEO_WORKER_TOKEN:}")String worker){this.jobs=jobs;this.users=users;this.observations=observations;adminToken=token(admin);workerToken=token(worker);}
    private static byte[] token(String raw){return raw.isBlank()?null:("Bearer "+raw).getBytes(StandardCharsets.UTF_8);}
    private boolean allowed(String auth,boolean worker,String actor,Long revision){byte[] expected=worker?workerToken:adminToken;return expected!=null&&auth!=null&&auth.length()<=1024&&MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))&&(worker||actor!=null&&revision!=null&&Boolean.TRUE.equals(users.status(actor,revision).get("valid")));}
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,boolean worker,String actor,Long revision,Supplier<Map<String,Object>> action){
        return Mono.fromCallable(()->{if(!allowed(auth,worker,actor,revision))return ResponseEntity.status(401).body(Map.<String,Object>of("code","unauthorized"));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get());}).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_specialist_request"))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","specialist_unavailable"))));
    }
    @GetMapping("/internal/specialist-jobs/capabilities")
    public Mono<ResponseEntity<Map<String,Object>>> capabilities(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return call(auth,false,actor,revision,jobs::capabilities);}
    @GetMapping("/internal/specialist-jobs/observations/{observation}")
    public Mono<ResponseEntity<Map<String,Object>>> observationJobs(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID observation,@RequestParam long observationVersion,@RequestParam(defaultValue="10")int limit,@RequestParam(required=false)UUID after){return call(auth,false,actor,revision,()->observations.list(observation,observationVersion,limit,after));}
    @PostMapping("/internal/specialist-jobs/inputs/{input}")
    public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID input,@RequestBody SpecialistJobs.Create body){return call(auth,false,actor,revision,()->jobs.create(input,body,actor));}
    @GetMapping("/internal/specialist-jobs/inputs/{input}")
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID input,@RequestParam(defaultValue="10")int limit,@RequestParam(required=false)UUID after){return call(auth,false,actor,revision,()->jobs.list(input,limit,after));}
    @GetMapping("/internal/specialist-jobs/inputs/{input}/selection")
    public Mono<ResponseEntity<Map<String,Object>>> selection(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID input,@RequestParam(required=false)String intent){return call(auth,false,actor,revision,()->jobs.selection(input,intent));}
    @GetMapping("/internal/specialist-jobs/{job}/result")
    public Mono<ResponseEntity<Map<String,Object>>> result(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID job){return call(auth,false,actor,revision,()->jobs.result(job));}
    @GetMapping("/internal/specialist-jobs/{job}")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID job){return call(auth,false,actor,revision,()->jobs.read(job));}
    @PostMapping("/internal/specialist-jobs/{job}/plate-review")
    public Mono<ResponseEntity<Map<String,Object>>> review(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID job,@RequestBody PlateEventResults.Review body){return call(auth,false,actor,revision,()->jobs.review(job,body,actor));}
    @PostMapping("/internal/specialist-jobs/{job}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID job,@RequestBody SpecialistJobs.Retry body){return call(auth,false,actor,revision,()->jobs.retry(job,body,actor));}
    public record Claim(String workerId,int leaseSeconds,String profile,List<String> operations,List<String> adapters){}
    public record Renewal(String workerId,long generation,int leaseSeconds){}
    public record Failure(String workerId,long generation,String errorCode){}
    @PostMapping("/internal/v1/specialist-jobs/claim")
    public Mono<ResponseEntity<Map<String,Object>>> claim(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Claim body){return call(auth,true,null,null,()->jobs.claim(body.workerId(),body.leaseSeconds(),body.profile(),body.operations(),body.adapters()==null?List.of():body.adapters()).map(value->Map.<String,Object>of("job",value)).orElseGet(()->Map.of("job_available",false)));}
    @PostMapping("/internal/v1/specialist-jobs/{job}/renew")
    public Mono<ResponseEntity<Map<String,Object>>> renew(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID job,@RequestBody Renewal body){return call(auth,true,null,null,()->{if(!jobs.renew(job,body.workerId(),body.generation(),body.leaseSeconds()))throw new InputRejected(409,"specialist_lease_expired");return Map.of("renewed",true);});}
    @PostMapping("/internal/v1/specialist-jobs/{job}/fail")
    public Mono<ResponseEntity<Map<String,Object>>> fail(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID job,@RequestBody Failure body){return call(auth,true,null,null,()->{if(!jobs.fail(job,body.workerId(),body.generation(),body.errorCode()))throw new InputRejected(409,"specialist_lease_expired");return Map.of("recorded",true);});}
    @PostMapping("/internal/v1/specialist-jobs/complete")
    public Mono<ResponseEntity<Map<String,Object>>> complete(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody SpecialistJobs.Complete body){return call(auth,true,null,null,()->jobs.complete(body));}
    @GetMapping("/internal/v1/specialist-jobs/{job}/image")
    public Mono<ResponseEntity<byte[]>> image(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID job,@RequestParam String workerId,@RequestParam long generation){
        return Mono.fromCallable(()->{if(!allowed(auth,true,null,null))return ResponseEntity.status(401).body(new byte[0]);var image=jobs.image(job,workerId,generation);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).header("X-Content-SHA256",image.sha256()).body(image.bytes());}).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(new byte[0])))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(new byte[0])));
    }
}
