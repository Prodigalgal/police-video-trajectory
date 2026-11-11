package cn.trial.videosearch.cluster;

import cn.trial.videosearch.evidence.InputRejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.slf4j.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/internal/cluster")
public class ClusterControlController {
    private static final Logger LOG=LoggerFactory.getLogger(ClusterControlController.class);
    private final SharedLoginState login;private final ModelAdmission admission;private final byte[] webToken,gatewayToken;
    public ClusterControlController(SharedLoginState login,ModelAdmission admission,@Value("${VIDEO_ADMIN_TOKEN:}")String web,@Value("${VIDEO_GATEWAY_TOKEN:}")String gateway){
        this.login=login;this.admission=admission;webToken=token(web);gatewayToken=token(gateway);
    }
    private static byte[] token(String value){return value.isBlank()?null:("Bearer "+value).getBytes(StandardCharsets.UTF_8);}
    private static ResponseEntity<Map<String,Object>> response(int status,Map<String,Object> value){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping("/readyz") public Mono<ResponseEntity<Map<String,Object>>> ready(){
        return Mono.fromCallable(()->{boolean ready=login.ready()&&admission.ready();return response(ready?200:503,Map.of("ready",ready));}).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(Exception.class,error->Mono.just(response(503,Map.of("ready",false))));
    }
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,byte[] expected,Supplier<Map<String,Object>> action){
        if(expected==null||auth==null||auth.length()>1024||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8)))return Mono.just(response(401,Map.of("code","unauthorized")));
        return Mono.fromCallable(()->response(200,action.get())).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,error->Mono.just(response(error.status,Map.of("code",error.code))))
            .onErrorResume(IllegalArgumentException.class,error->Mono.just(response(400,Map.of("code","invalid_coordination_request"))))
            .onErrorResume(Exception.class,error->{LOG.warn("Cluster coordination unavailable type={}",error.getClass().getSimpleName());return Mono.just(response(503,Map.of("code","cluster_coordination_unavailable")));});
    }
    @PostMapping("/login/issue") public Mono<ResponseEntity<Map<String,Object>>> issue(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody SharedLoginState.Issue body){return call(auth,webToken,()->login.issue(body));}
    @PostMapping("/login/consume") public Mono<ResponseEntity<Map<String,Object>>> consume(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody SharedLoginState.Consume body){return call(auth,webToken,()->login.consume(body));}
    @PostMapping("/login/clear") public Mono<ResponseEntity<Map<String,Object>>> clear(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody SharedLoginState.Clear body){return call(auth,webToken,()->login.clear(body));}
    @PostMapping("/models/acquire") public Mono<ResponseEntity<Map<String,Object>>> acquire(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ModelAdmission.Acquire body){return call(auth,gatewayToken,()->admission.acquire(body));}
    @PostMapping("/models/release") public Mono<ResponseEntity<Map<String,Object>>> release(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ModelAdmission.Release body){return call(auth,gatewayToken,()->admission.release(body));}
}
