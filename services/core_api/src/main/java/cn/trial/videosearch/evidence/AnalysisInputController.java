package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
@RequestMapping("/internal/analysis-inputs")
public class AnalysisInputController {
    private final AnalysisInputs inputs;
    private final UserService users;
    private final byte[] expected;
    public AnalysisInputController(AnalysisInputs inputs,UserService users,@Value("${VIDEO_ADMIN_TOKEN:}")String token){
        this.inputs=inputs;this.users=users;expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    private void authorize(String auth,String actor,Long revision){
        if(expected==null||auth==null||auth.length()>1024||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))||actor==null||revision==null||!Boolean.TRUE.equals(users.status(actor,revision).get("valid")))throw new AdminService.Rejected(401,"unauthorized");
    }
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,String actor,Long revision,Supplier<Map<String,Object>> operation){
        return Mono.fromCallable(()->{authorize(auth,actor,revision);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operation.get());})
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_input_request"))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","analysis_inputs_unavailable"))));
    }
    @PostMapping
    public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody AnalysisInputs.Create body){return call(auth,actor,revision,()->inputs.create(body,actor));}
    @GetMapping
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,
        @RequestParam(name="observation_id")UUID observation,@RequestParam(required=false)String purpose,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){return call(auth,actor,revision,()->inputs.list(observation,purpose,limit,after));}
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return call(auth,actor,revision,()->inputs.read(id));}
    @GetMapping("/{id}/manifest")
    public Mono<ResponseEntity<byte[]>> manifest(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){
        return Mono.fromCallable(()->{
            authorize(auth,actor,revision);var verified=inputs.verify(id,null);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).header("X-Content-SHA256",verified.sha256()).body(verified.payload().getBytes(StandardCharsets.UTF_8));
        }).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(failure(e.status,e.code)))
            .onErrorResume(InputRejected.class,e->Mono.just(failure(e.status,e.code)))
            .onErrorResume(Exception.class,e->Mono.just(failure(503,"analysis_inputs_unavailable")));
    }
    private static ResponseEntity<byte[]> failure(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(("{\"code\":\""+code+"\"}").getBytes(StandardCharsets.UTF_8));}
}
