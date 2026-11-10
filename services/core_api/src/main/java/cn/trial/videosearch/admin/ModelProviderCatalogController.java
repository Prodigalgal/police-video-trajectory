package cn.trial.videosearch.admin;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/internal/admin/models/providers")
public class ModelProviderCatalogController {
    private final BusinessEndpoint endpoint;private final ModelProviderCatalogs catalogs;
    public ModelProviderCatalogController(BusinessEndpoint endpoint,ModelProviderCatalogs catalogs){this.endpoint=endpoint;this.catalogs=catalogs;}
    @GetMapping("/{id}/models")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable String id){
        return endpoint.call(auth,actor,revision,true,()->catalogs.read(id,false,actor));
    }
    @PostMapping("/{id}/models/refresh")
    public Mono<ResponseEntity<Map<String,Object>>> refresh(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable String id){
        return endpoint.call(auth,actor,revision,true,()->catalogs.read(id,true,actor));
    }
}
