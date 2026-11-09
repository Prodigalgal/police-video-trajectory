package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.support.ActorAccess;
import cn.trial.videosearch.support.BusinessEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@RestController
@RequestMapping("/internal/processing-traces")
public class ProcessingTraceController {
    private static final Logger LOG=LoggerFactory.getLogger(ProcessingTraceController.class);
    private final ProcessingTrace traces;
    private final BusinessEndpoint endpoint;
    private final ActorAccess access;
    public ProcessingTraceController(ProcessingTrace traces,BusinessEndpoint endpoint,ActorAccess access){this.traces=traces;this.endpoint=endpoint;this.access=access;}
    @GetMapping("/observations/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> observation(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam long observationVersion){
        return endpoint.call(auth,actor,revision,false,()->{if(observationVersion<1)throw new IllegalArgumentException();var result=traces.observation(id,observationVersion);traces.audit(UUID.fromString((String)result.get("frame_id")),actor,"detail");return result;});
    }
    @GetMapping("/frames/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> frame(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,true,()->{var result=traces.frame(id);traces.audit(id,actor,"detail");return result;});}
    @GetMapping("/frames/{id}/image")
    public Mono<ResponseEntity<byte[]>> image(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam String sha){
        return Mono.fromCallable(()->{access.require(auth,actor,revision,true);var image=traces.image(id,sha);traces.audit(id,actor,"image");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-SHA256",image.sha256()).contentType(MediaType.IMAGE_JPEG).body(image.bytes());})
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).build()))
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).build()))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().build()))
            .onErrorResume(Exception.class,e->{LOG.warn("Processing trace image read failed",e);return Mono.just(ResponseEntity.status(503).build());});
    }
}
