package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/internal/specialist-jobs/control")
public class SpecialistOperationsController {
    private final BusinessEndpoint endpoint;private final SpecialistOperations operations;
    public SpecialistOperationsController(BusinessEndpoint endpoint,SpecialistOperations operations){this.endpoint=endpoint;this.operations=operations;}
    @GetMapping
    public Mono<ResponseEntity<Map<String,Object>>> status(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,true,operations::status);}
    @PostMapping
    public Mono<ResponseEntity<Map<String,Object>>> control(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody SpecialistOperations.Control body){return endpoint.call(auth,actor,revision,true,()->operations.control(body,actor));}
    @PostMapping("/plate-index/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,true,()->operations.retryPlateIndex(actor));}
}
