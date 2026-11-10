package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/internal/specialist-jobs/observations")
public class ReviewedPlateSearchController {
    private final BusinessEndpoint endpoint;private final ReviewedPlateSearchStatus status;
    public ReviewedPlateSearchController(BusinessEndpoint endpoint,ReviewedPlateSearchStatus status){this.endpoint=endpoint;this.status=status;}
    @GetMapping("/{id}/plate-search")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,
            @PathVariable UUID id,@RequestParam long observationVersion){
        return endpoint.call(auth,actor,revision,false,()->status.read(id,observationVersion));
    }
}
