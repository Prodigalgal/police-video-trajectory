package cn.trial.videosearch.pipeline;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/internal/pipeline/features")
public class PipelineFeaturesController {
    private final PipelineFeatures features;private final BusinessEndpoint endpoint;
    public PipelineFeaturesController(PipelineFeatures features,BusinessEndpoint endpoint){this.features=features;this.endpoint=endpoint;}
    @GetMapping public Mono<ResponseEntity<Map<String,Object>>> get(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,true,features::snapshot);}
    @PutMapping("/{feature}") public Mono<ResponseEntity<Map<String,Object>>> put(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable String feature,@RequestBody PipelineFeatures.Update command){return endpoint.call(auth,actor,revision,true,()->features.update(feature,command,actor));}
}
