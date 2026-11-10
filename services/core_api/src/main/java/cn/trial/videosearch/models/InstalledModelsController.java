package cn.trial.videosearch.models;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/internal/models/local-catalogs")
public class InstalledModelsController {
    private final InstalledModelBindings bindings;private final BusinessEndpoint endpoint;
    public InstalledModelsController(InstalledModelBindings bindings,BusinessEndpoint endpoint){this.bindings=bindings;this.endpoint=endpoint;}
    @GetMapping public Mono<ResponseEntity<Map<String,Object>>> get(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,true,bindings::list);}
    @PutMapping("/{kind}") public Mono<ResponseEntity<Map<String,Object>>> put(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable String kind,@RequestBody ManagedCatalogStore.Command command){return endpoint.call(auth,actor,revision,true,()->bindings.save(kind,command,actor));}
}
