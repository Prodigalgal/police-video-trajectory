package cn.trial.videosearch.ingestion;

import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/internal/ingestion")
public class IngestionController {
    private final IngestionQueries queries;private final IngestionCommands commands;private final BusinessEndpoint endpoint;
    public IngestionController(IngestionQueries queries,IngestionCommands commands,BusinessEndpoint endpoint){this.queries=queries;this.commands=commands;this.endpoint=endpoint;}
    @GetMapping("/sources") public Mono<ResponseEntity<Map<String,Object>>> sources(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestParam(defaultValue="30")int limit,@RequestParam(required=false)UUID after){return endpoint.call(auth,actor,revision,true,()->queries.sources(limit,after));}
    @PostMapping("/sources") public Mono<ResponseEntity<Map<String,Object>>> register(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody IngestionCommands.SourceCreate body){return endpoint.call(auth,actor,revision,true,()->commands.register(body,actor));}
    @PutMapping("/sources/{id}") public Mono<ResponseEntity<Map<String,Object>>> toggle(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestBody IngestionCommands.SourceToggle body){return endpoint.call(auth,actor,revision,true,()->commands.toggle(id,body,actor));}
    @PostMapping("/batches") public Mono<ResponseEntity<Map<String,Object>>> create(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody IngestionCommands.CreateBatch body){return endpoint.call(auth,actor,revision,true,()->commands.create(body,actor));}
    @GetMapping("/tasks") public Mono<ResponseEntity<Map<String,Object>>> tasks(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestParam(defaultValue="all")String state,@RequestParam(name="camera_id",required=false)String camera,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after,@RequestParam(name="batch_id",required=false)UUID batch){return endpoint.call(auth,actor,revision,true,()->queries.tasks(state,camera,limit,after,batch));}
    @PostMapping("/tasks/{id}/control") public Mono<ResponseEntity<Map<String,Object>>> control(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestBody IngestionCommands.Control body){return endpoint.call(auth,actor,revision,true,()->commands.control(id,body,actor));}
    @GetMapping("/control") public Mono<ResponseEntity<Map<String,Object>>> global(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,true,commands::global);}
    @PostMapping("/control") public Mono<ResponseEntity<Map<String,Object>>> global(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestBody IngestionCommands.GlobalControl body){return endpoint.call(auth,actor,revision,true,()->commands.global(body,actor));}
}
