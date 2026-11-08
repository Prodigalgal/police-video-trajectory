package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.ActorAccess;
import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/internal/appearance")
public class AppearanceController {
    private final BusinessEndpoint endpoint;private final ActorAccess access;private final AppearanceJobs jobs;private final AppearanceSearch search;
    public AppearanceController(BusinessEndpoint endpoint,ActorAccess access,AppearanceJobs jobs,AppearanceSearch search){this.endpoint=endpoint;this.access=access;this.jobs=jobs;this.search=search;}
    @GetMapping("/capabilities")
    public Mono<ResponseEntity<Map<String,Object>>> capabilities(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,false,jobs::capabilities);}
    @PostMapping("/queries")
    public Mono<ResponseEntity<Map<String,Object>>> query(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody AppearanceSearch.Query body){return endpoint.call(auth,actor,revision,false,()->{var value=search.create(body,actor,revision);access.require(auth,actor,revision,false);return value;});}
    @GetMapping("/queries/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> page(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="10")int limit){return endpoint.call(auth,actor,revision,false,()->{var value=search.page(id,actor,revision,offset,limit);access.require(auth,actor,revision,false);return value;});}
    @DeleteMapping("/queries/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> close(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,false,()->search.close(id,actor,revision));}
    public record Produce(int limit){}
    @PostMapping("/produce")
    public Mono<ResponseEntity<Map<String,Object>>> produce(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody Produce body){return endpoint.call(auth,actor,revision,true,()->jobs.produce(body.limit()));}
    public record Control(String control,Boolean enabled){}
    @PostMapping("/controls")
    public Mono<ResponseEntity<Map<String,Object>>> control(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody Control body){return endpoint.call(auth,actor,revision,true,()->{if(body.enabled()==null)throw new IllegalArgumentException();return jobs.setControl(body.control(),body.enabled());});}
    @GetMapping("/jobs")
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){return endpoint.call(auth,actor,revision,true,()->jobs.list(limit,after));}
    @PostMapping("/jobs/{id}/retry")
    public Mono<ResponseEntity<Map<String,Object>>> retry(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,true,()->jobs.retry(id));}
}
