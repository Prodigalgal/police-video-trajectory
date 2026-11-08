package cn.trial.videosearch.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Supplier;

@RestController
public class UserController {
    public record Credential(String username) {}
    public record Status(String username,long revision) {}
    public record Delete(long expectedVersion) {}
    private final UserService users;private final byte[] expected;
    public UserController(UserService users,@Value("${VIDEO_ADMIN_TOKEN:}") String token) {this.users=users;this.expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,String actor,boolean administrative,Supplier<Map<String,Object>> action) {
        if(expected==null || auth==null || auth.length()>1024 || !MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))) return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->{if(administrative)users.requireAdmin(actor);return ResponseEntity.ok(action.get());}).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
                .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_user_request"))))
                .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","account_service_unavailable"))));
    }
    @PostMapping("/internal/auth/credential")
    public Mono<ResponseEntity<Map<String,Object>>> credential(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Credential body) {
        return call(auth,null,false,()->users.credential(body.username()).orElseThrow(()->new AdminService.Rejected(404,"invalid_credentials")));
    }
    @PostMapping("/internal/auth/status")
    public Mono<ResponseEntity<Map<String,Object>>> status(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Status body) {return call(auth,null,false,()->users.status(body.username(),body.revision()));}
    @GetMapping("/internal/admin/users")
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestParam(defaultValue="50")int limit,@RequestParam(required=false)String after) {return call(auth,actor,true,()->users.list(limit,after));}
    @PostMapping("/internal/admin/users")
    public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestBody UserService.Create body) {return call(auth,actor,true,()->users.create(body,actor));}
    @PutMapping("/internal/admin/users/{username}")
    public Mono<ResponseEntity<Map<String,Object>>> update(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@PathVariable String username,@RequestBody UserService.Update body) {return call(auth,actor,true,()->users.update(username,body,actor));}
    @PostMapping("/internal/admin/users/{username}/password")
    public Mono<ResponseEntity<Map<String,Object>>> reset(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@PathVariable String username,@RequestBody UserService.Reset body) {return call(auth,actor,true,()->users.reset(username,body,actor));}
    @DeleteMapping("/internal/admin/users/{username}")
    public Mono<ResponseEntity<Map<String,Object>>> remove(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@PathVariable String username,@RequestBody Delete body) {return call(auth,actor,true,()->users.remove(username,body.expectedVersion(),actor));}
}
