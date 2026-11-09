package cn.trial.videosearch.admin;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
public class ModelController {
    private static final Logger LOG=LoggerFactory.getLogger(ModelController.class);
    private final ModelRegistry registry;
    private final BusinessEndpoint endpoint;
    private final byte[] readerToken;
    public ModelController(ModelRegistry registry,BusinessEndpoint endpoint,@Value("${VIDEO_GATEWAY_TOKEN:}")String reader){this.registry=registry;this.endpoint=endpoint;readerToken=token(reader);}
    private static byte[] token(String raw){return raw.isBlank()?null:("Bearer "+raw).getBytes(StandardCharsets.UTF_8);}
    private Mono<ResponseEntity<Map<String,Object>>> read(String auth,Supplier<Map<String,Object>> action){
        byte[] expected=readerToken;
        if(expected==null)return Mono.just(ResponseEntity.status(503).body(Map.of("code","model_control_unavailable")));
        if(auth==null||auth.length()>1024||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8)))return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->ResponseEntity.ok().header("Cache-Control","no-store").body(action.get())).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,error->Mono.just(ResponseEntity.status(error.status).body(Map.of("code",error.code))))
            .onErrorResume(IllegalArgumentException.class,error->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_model_request"))))
            .onErrorResume(Exception.class,error->{LOG.warn("Model control operation unavailable ({})",error.getClass().getSimpleName());return Mono.just(ResponseEntity.status(503).body(Map.of("code","model_control_unavailable")));});
    }
    @GetMapping("/internal/admin/models/configuration")
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,true,registry::list);}
    @PutMapping("/internal/admin/models/profiles/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> save(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestBody ModelRegistry.Save body){return endpoint.call(auth,actor,revision,true,()->registry.save(id,body,actor));}
    @PostMapping("/internal/admin/models/bindings")
    public Mono<ResponseEntity<Map<String,Object>>> bind(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody ModelRegistry.Bind body){return endpoint.call(auth,actor,revision,true,()->registry.bind(body,actor));}
    @GetMapping("/internal/v1/model-bindings")
    public Mono<ResponseEntity<Map<String,Object>>> runtime(@RequestHeader(value="Authorization",required=false)String auth){return read(auth,registry::runtime);}
    @PostMapping("/internal/v1/model-bindings/acknowledge")
    public Mono<ResponseEntity<Map<String,Object>>> acknowledge(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ModelRegistry.Receipt body){return read(auth,()->registry.acknowledge(body));}
}
