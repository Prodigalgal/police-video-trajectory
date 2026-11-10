package cn.trial.videosearch.pipeline;

import cn.trial.videosearch.admin.AdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
public class PipelineFeatureWorkerController {
    private static final Logger LOG=LoggerFactory.getLogger(PipelineFeatureWorkerController.class);
    private final PipelineFeatures features;private final byte[] expected;
    public PipelineFeatureWorkerController(PipelineFeatures features,@Value("${VIDEO_WORKER_TOKEN:}")String token){this.features=features;expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    @GetMapping("/internal/v2/pipeline/features")
    public Mono<ResponseEntity<Map<String,Object>>> get(@RequestHeader(value="Authorization",required=false)String auth){
        if(expected==null||auth==null||auth.length()>1024||!MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8)))return Mono.just(response(401,Map.of("code","unauthorized")));
        return Mono.fromCallable(()->response(200,features.snapshot())).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(response(e.status,Map.of("code",e.code))))
            .onErrorResume(Exception.class,e->{LOG.warn("Optional pipeline configuration unavailable",e);return Mono.just(response(503,Map.of("code","pipeline_features_unavailable")));});
    }
    private static ResponseEntity<Map<String,Object>> response(int status,Map<String,Object> body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
