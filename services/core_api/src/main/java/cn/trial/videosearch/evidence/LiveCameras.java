package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

/** Only project-scoped registered cameras are visible; transport addresses stay private. */
@RestController
public class LiveCameras {
    private final JdbcTemplate db;
    private final SearchOrchestrator scope;
    public LiveCameras(JdbcTemplate db,SearchOrchestrator scope){this.db=db;this.scope=scope;}
    @GetMapping("/api/v1/live/cameras")
    public Mono<ResponseEntity<Map<String,Object>>> cameras(@RequestHeader(value="Authorization",required=false)String auth){
        if(!scope.authorized(auth))return Mono.just(ResponseEntity.status(401).build());
        return Mono.fromCallable(()->ResponseEntity.ok(list())).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(Exception.class,error->Mono.just(ResponseEntity.status(503).body(Map.of("code","live_unavailable"))));
    }
    Map<String,Object> list(){
        var ids=scope.readableCameras().stream().sorted().toList();
        if(ids.isEmpty())return Map.of("items",List.of());
        var rows=db.queryForList("SELECT c.camera_id,c.display_name FROM cameras c WHERE c.enabled AND c.camera_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND EXISTS (SELECT 1 FROM sources s WHERE s.camera_id=c.camera_id AND s.source_type='rtsp') ORDER BY c.camera_id",ids.toArray());
        return Map.of("items",rows);
    }
}
