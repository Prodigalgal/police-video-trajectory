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
public class ObservationContextController {
    private final TemporalEvidence evidence;
    private final SearchOrchestrator search;
    public ObservationContextController(TemporalEvidence evidence,SearchOrchestrator search){this.evidence=evidence;this.search=search;}
    @GetMapping("/api/v1/observations/{id}/context")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,
        @RequestParam(name="expected_version",required=false)Long expected,@RequestParam(name="before_ms",defaultValue="60000")int before,
        @RequestParam(name="after_ms",defaultValue="60000")int after,@RequestParam(name="max_frames",defaultValue="16")int frames,
        @RequestParam(name="max_gap_ms",defaultValue="6000")int gap){
        if(!search.authorized(auth))return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->{
            if(expected!=null&&expected<1)throw new IllegalArgumentException();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence.context(id,expected,new TemporalWindow(before,after,frames,gap)).view());
        }).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_input_request"))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","analysis_inputs_unavailable"))));
    }
}
