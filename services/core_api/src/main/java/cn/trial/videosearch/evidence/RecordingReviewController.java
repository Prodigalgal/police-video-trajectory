package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.support.*;
import org.slf4j.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@RestController
@RequestMapping("/internal/recording-review")
public class RecordingReviewController {
    private static final Logger LOG=LoggerFactory.getLogger(RecordingReviewController.class);
    private final RecordingReviewQueries queries;
    private final BusinessEndpoint endpoint;
    private final ActorAccess access;
    public RecordingReviewController(RecordingReviewQueries queries,BusinessEndpoint endpoint,ActorAccess access){this.queries=queries;this.endpoint=endpoint;this.access=access;}
    @GetMapping("/frames")
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestParam(name="camera_id",required=false)String camera,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){return endpoint.call(auth,actor,revision,true,()->queries.list(camera,limit,after));}
    @GetMapping("/frames/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> detail(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,true,()->{var value=queries.detail(id);queries.audit(id,actor,"detail");return value;});}
    @FunctionalInterface private interface Binary<T>{ResponseEntity<T> read()throws Exception;}
    private <T> Mono<ResponseEntity<T>> binary(String auth,String actor,Long revision,Binary<T> read){
        return Mono.fromCallable(()->{access.require(auth,actor,revision,true);return read.read();}).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).build()))
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).build()))
            .onErrorResume(Exception.class,e->{LOG.warn("Recording review media read failed",e);return Mono.just(ResponseEntity.status(503).build());});
    }
    @GetMapping("/frames/{id}/image")
    public Mono<ResponseEntity<byte[]>> image(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return binary(auth,actor,revision,()->{var image=queries.image(id);queries.audit(id,actor,"image");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-SHA256",image.sha256()).contentType(MediaType.IMAGE_JPEG).body(image.bytes());});}
    @GetMapping("/frames/{id}/video")
    public Mono<ResponseEntity<Flux<DataBuffer>>> video(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestHeader(value="Range",required=false)String range,@PathVariable UUID id){return binary(auth,actor,revision,()->{var response=MediaRangeResponse.read(queries.playback(id).path(),range);if(response.getStatusCode().is2xxSuccessful())queries.audit(id,actor,"video");return response;});}
}
