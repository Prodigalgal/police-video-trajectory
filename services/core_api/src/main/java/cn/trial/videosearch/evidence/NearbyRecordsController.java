package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

@RestController
public class NearbyRecordsController {
    private static final Logger LOG=LoggerFactory.getLogger(NearbyRecordsController.class);
    private final NearbyRecords records;
    private final SearchOrchestrator search;
    public NearbyRecordsController(NearbyRecords records,SearchOrchestrator search){this.records=records;this.search=search;}
    @GetMapping("/api/v1/observations/{id}/nearby-records")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,
        @RequestParam(name="expected_version",required=false)Long expected,@RequestParam(defaultValue="9")int limit){
        if(!search.authorized(auth))return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->{if(expected!=null&&expected<1)throw new IllegalArgumentException();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.list(id,expected,limit));})
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_input_request"))))
            .onErrorResume(Exception.class,e->{LOG.warn("Nearby records unavailable observation={}",id,e);return Mono.just(ResponseEntity.status(503).body(Map.of("code","analysis_inputs_unavailable")));});
    }
}
