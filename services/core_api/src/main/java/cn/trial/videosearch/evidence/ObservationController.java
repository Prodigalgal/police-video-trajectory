package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;
import java.util.UUID;

@RestController
public class ObservationController {
    private final ObservationDetails details;
    private final SearchOrchestrator search;
    public ObservationController(ObservationDetails details,SearchOrchestrator search) { this.details=details;this.search=search; }
    @GetMapping("/api/v1/observations/{observationId}")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID observationId) {
        if(!search.authorized(auth)) return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->details.read(observationId).map(value->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                        .orElseGet(()->ResponseEntity.status(404).body(Map.<String,Object>of("code","observation_unavailable"))))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","evidence_unavailable"))));
    }
}
