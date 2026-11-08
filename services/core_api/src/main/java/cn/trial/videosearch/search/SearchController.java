package cn.trial.videosearch.search;

import cn.trial.videosearch.http.SecureJsonClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
public final class SearchController {
    private final SearchOrchestrator search;

    public SearchController(SearchOrchestrator search) { this.search = search; }

    public record SearchRequest(String query,Integer pageSize,String cursor,SearchRefinement.Command adjustment,String mode) {
        public SearchRequest(String query,Integer pageSize,String cursor){this(query,pageSize,cursor,null,null);}
        public SearchRequest(String query,Integer pageSize,String cursor,SearchRefinement.Command adjustment){this(query,pageSize,cursor,adjustment,null);}
    }
    public record CloseRequest(String query,String cursor) {}

    @GetMapping("/healthz")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @PostMapping("/api/v1/search")
    public Mono<ResponseEntity<Map<String, Object>>> search(@RequestHeader(value = "Authorization", required = false) String auth,
                                                             @RequestBody SearchRequest request) {
        if (!search.authorized(auth)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "未授权访问")));
        }
        return Mono.fromCallable(() -> ResponseEntity.ok(search.search(request.query(),request.pageSize()==null?20:request.pageSize(),request.cursor(),request.adjustment(),request.mode()==null?"natural":request.mode())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(SearchContextCodec.Invalid.class,e->Mono.just(ResponseEntity.status(e.code.equals("search_context_expired")?410:400).body(Map.of("code",e.code,"detail","查询条件已过期或变化，请重新搜索"))))
                .onErrorResume(SearchCursorCodec.Invalid.class,e->Mono.just(ResponseEntity.status(e.code.equals("search_cursor_expired")?410:400).body(Map.of("code",e.code,"detail","翻页会话已过期或无效，请重新搜索"))))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest().body(Map.of("detail", "查询内容无效"))))
                .onErrorResume(SearchOrchestrator.SearchFailure.class, e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("detail", "检索依赖暂不可用", "code", e.code()))))
                .onErrorResume(SecureJsonClient.UpstreamFailure.class, e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("detail", "检索依赖暂不可用"))))
                .onErrorResume(Exception.class, e -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", "检索服务暂不可用"))));
    }

    @PostMapping("/api/v1/search/close")
    public Mono<ResponseEntity<Map<String,Object>>> close(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody CloseRequest request) {
        if(!search.authorized(auth)) return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->{search.closeSearch(request.query(),request.cursor());return ResponseEntity.ok(Map.<String,Object>of("closed",true));})
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(SearchCursorCodec.Invalid.class,e->Mono.just(ResponseEntity.status(400).body(Map.of("code",e.code))))
                .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_request"))))
                .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","search_backend_unavailable"))));
    }
}
