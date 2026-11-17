package cn.trial.videosearch.investigation;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/internal/investigation/mtmc")
public class MtmcController {
    private final MtmcQueries queries;private final BusinessEndpoint endpoint;
    public MtmcController(MtmcQueries queries,BusinessEndpoint endpoint){this.queries=queries;this.endpoint=endpoint;}
    @GetMapping("/capabilities")
    public Mono<ResponseEntity<Map<String,Object>>> capabilities(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,false,queries::capabilities);}
    @PostMapping("/queries")
    public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody MtmcQueries.Query body){return endpoint.call(auth,actor,revision,false,()->queries.create(body,actor,revision));}
    @GetMapping("/queries/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> page(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String run){return endpoint.call(auth,actor,revision,false,()->queries.page(id,actor,revision,offset,limit,run));}
    @DeleteMapping("/queries/{id}")
    public Mono<ResponseEntity<Map<String,Object>>> close(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,false,()->queries.close(id,actor,revision));}
}
