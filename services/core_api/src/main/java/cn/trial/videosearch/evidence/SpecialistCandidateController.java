package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.AdminService;
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
@RequestMapping("/internal/specialist-jobs/candidates")
public class SpecialistCandidateController {
    private final SpecialistCandidates candidates;
    private final UserService users;
    private final byte[] expected;
    public SpecialistCandidateController(SpecialistCandidates candidates,UserService users,@Value("${VIDEO_ADMIN_TOKEN:}")String token){
        this.candidates=candidates;this.users=users;this.expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,String actor,Long revision,Supplier<Map<String,Object>> action){
        return Mono.fromCallable(()->{
            if(expected==null||auth==null||auth.length()>1024||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))
                ||actor==null||revision==null||!Boolean.TRUE.equals(users.status(actor,revision).get("valid")))
                return ResponseEntity.status(401).body(Map.<String,Object>of("code","unauthorized"));
            users.requireAdmin(actor);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get());
        }).subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
        .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
        .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_candidate_request"))))
        .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","candidate_unavailable"))));
    }
    @GetMapping
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,
        @RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,
        @RequestParam(name="camera_id",required=false)String camera,@RequestParam(required=false)String state,
        @RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){
        return call(auth,actor,revision,()->candidates.list(camera,state,limit,after));
    }
    @PostMapping("/{candidate}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false)String auth,
        @RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,
        @PathVariable UUID candidate,@RequestBody SpecialistCandidates.Retry body){return call(auth,actor,revision,()->candidates.retry(candidate,body,actor));}
}
