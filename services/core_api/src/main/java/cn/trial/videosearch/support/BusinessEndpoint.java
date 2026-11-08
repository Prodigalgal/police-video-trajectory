package cn.trial.videosearch.support;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.evidence.InputRejected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

/** Common blocking boundary for new business controllers, without domain decisions. */
@Component
public class BusinessEndpoint {
    private static final Logger LOG=LoggerFactory.getLogger(BusinessEndpoint.class);
    private final ActorAccess access;
    public BusinessEndpoint(ActorAccess access){this.access=access;}
    @FunctionalInterface public interface Action {Map<String,Object> run() throws Exception;}
    public Mono<ResponseEntity<Map<String,Object>>> call(String auth,String actor,Long revision,boolean admin,Action action){
        return Mono.fromCallable(()->{access.require(auth,actor,revision,admin);return response(200,action.run());})
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(response(e.status,Map.of("code",e.code))))
            .onErrorResume(InputRejected.class,e->Mono.just(response(e.status,Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(response(400,Map.of("code","invalid_business_request"))))
            .onErrorResume(Exception.class,e->{LOG.warn("Business request failed",e);return Mono.just(response(503,Map.of("code","business_unavailable")));});
    }
    private static ResponseEntity<Map<String,Object>> response(int status,Map<String,Object> body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
