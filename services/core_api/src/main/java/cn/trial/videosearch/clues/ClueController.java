package cn.trial.videosearch.clues;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/clues/collections")
public class ClueController {
    private final ClueCollections service;private final UserService users;private final byte[] expected;
    public ClueController(ClueCollections service,UserService users,@Value("${VIDEO_ADMIN_TOKEN:}")String token) {
        this.service=service;this.users=users;expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    @FunctionalInterface interface Action {Map<String,Object> run() throws Exception;}
    private void authorize(String auth,String actor,Long revision) {
        if(expected==null || auth==null || auth.length()>1024 || !MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))
                || actor==null || revision==null || !Boolean.TRUE.equals(users.status(actor,revision).get("valid")))throw new AdminService.Rejected(401,"unauthorized");
    }
    private Mono<ResponseEntity<Map<String,Object>>> call(String auth,String actor,Long revision,Action action) {
        return Mono.fromCallable(()->{authorize(auth,actor,revision);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.run());})
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_clue_request"))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","clues_unavailable"))));
    }
    @GetMapping
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,
            @RequestParam(defaultValue="open")String state,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after) {return call(auth,actor,revision,()->service.list(state,limit,after));}
    @PostMapping
    public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody ClueCollections.Create body) {return call(auth,actor,revision,()->service.create(body,actor));}
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id) {return call(auth,actor,revision,()->service.read(id));}
    @PutMapping("/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> edit(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestBody ClueCollections.Edit body) {return call(auth,actor,revision,()->service.edit(id,body,actor));}
    @PutMapping("/{id}/items/{observation}")
    public Mono<ResponseEntity<Map<String,Object>>> add(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@PathVariable UUID observation,@RequestBody ClueCollections.Add body) {return call(auth,actor,revision,()->service.add(id,observation,body,actor));}
    @PutMapping("/{id}/items/{observation}/review")
    public Mono<ResponseEntity<Map<String,Object>>> review(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@PathVariable UUID observation,@RequestBody ClueCollections.Review body) {return call(auth,actor,revision,()->service.review(id,observation,body,actor));}
    @PutMapping("/{id}/links/{from}/{to}")
    public Mono<ResponseEntity<Map<String,Object>>> link(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@PathVariable UUID from,@PathVariable UUID to,@RequestBody ClueCollections.Link body) {return call(auth,actor,revision,()->service.link(id,from,to,body,actor));}
    @GetMapping("/{id}/export")
    public Mono<ResponseEntity<byte[]>> export(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long authRevision,@PathVariable UUID id,@RequestParam long revision,@RequestParam(defaultValue="json")String format) {
        return Mono.fromCallable(()->{authorize(auth,actor,authRevision);var data=service.export(id,revision,format,actor);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Type",data.mediaType())
                .header("Content-Disposition","attachment; filename=\""+data.filename()+"\"").header("X-Content-SHA256",data.sha256()).body(data.bytes());})
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(AdminService.Rejected.class,e->Mono.just(ResponseEntity.status(e.status).body(("{\"code\":\""+e.code+"\"}").getBytes(StandardCharsets.UTF_8))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body("{\"code\":\"invalid_clue_request\"}".getBytes(StandardCharsets.UTF_8))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body("{\"code\":\"clues_unavailable\"}".getBytes(StandardCharsets.UTF_8))));
    }
}
