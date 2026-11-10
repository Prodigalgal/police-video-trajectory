package cn.trial.videosearch.exports;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/internal/exports")
public class ExportController {
    private final ExportJobs jobs;private final BusinessEndpoint endpoint;private final ExportRuntime runtime;
    public ExportController(ExportJobs jobs,BusinessEndpoint endpoint,ExportRuntime runtime){this.jobs=jobs;this.endpoint=endpoint;this.runtime=runtime;}
    @GetMapping("/status") public Mono<ResponseEntity<Map<String,Object>>> status(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,false,runtime::status);}
    @GetMapping("/info") public Mono<ResponseEntity<Map<String,Object>>> info(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestParam(name="collection_id")UUID collection,@RequestParam(name="revision")long expected){return endpoint.call(auth,actor,revision,false,()->jobs.info(collection,expected));}
    @PostMapping public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody ExportJobs.Create body){return endpoint.call(auth,actor,revision,false,()->jobs.create(body,actor,revision));}
    @GetMapping public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestParam(name="collection_id")UUID collection,@RequestParam(defaultValue="10")int limit,@RequestParam(required=false)UUID after){return endpoint.call(auth,actor,revision,false,()->jobs.list(collection,actor,limit,after));}
    @GetMapping("/{id}") public Mono<ResponseEntity<Map<String,Object>>> read(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,false,()->jobs.read(id,actor));}
    @PostMapping("/{id}/control") public Mono<ResponseEntity<Map<String,Object>>> control(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestBody ExportJobs.Control body){return endpoint.call(auth,actor,revision,false,()->jobs.control(id,body,actor,revision));}
    @GetMapping("/{id}/transfers") public Mono<ResponseEntity<Map<String,Object>>> transfers(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam(defaultValue="10")int limit,@RequestParam(required=false)UUID after){return endpoint.call(auth,actor,revision,false,()->jobs.transfers(id,actor,limit,after));}
}
