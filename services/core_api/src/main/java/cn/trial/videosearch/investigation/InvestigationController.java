package cn.trial.videosearch.investigation;

import cn.trial.videosearch.geography.CameraLocations;
import cn.trial.videosearch.support.BusinessEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/internal/investigation")
public class InvestigationController {
    private final InvestigationService service;
    private final CameraLocations locations;
    private final BusinessEndpoint endpoint;
    private final EvidencePackageManifest packages;
    public InvestigationController(InvestigationService service,CameraLocations locations,BusinessEndpoint endpoint,EvidencePackageManifest packages){this.service=service;this.locations=locations;this.endpoint=endpoint;this.packages=packages;}
    @GetMapping("/capabilities")
    public Mono<ResponseEntity<Map<String,Object>>> capabilities(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,false,service::capabilities);}
    @GetMapping("/observations/{id}/timeline")
    public Mono<ResponseEntity<Map<String,Object>>> timeline(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam(name="expected_version",required=false)Long expected,@RequestParam(name="before_ms",defaultValue="60000")int before,@RequestParam(name="after_ms",defaultValue="60000")int after,@RequestParam(name="max_gap_ms",defaultValue="10000")int gap){return endpoint.call(auth,actor,revision,false,()->service.timeline(id,expected,before,after,gap));}
    @GetMapping("/observations/{id}/candidates")
    public Mono<ResponseEntity<Map<String,Object>>> candidates(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id,@RequestParam(name="expected_version",required=false)Long expected,@RequestParam(defaultValue="600")int seconds,@RequestParam(defaultValue="20")int limit){return endpoint.call(auth,actor,revision,false,()->service.candidates(id,expected,seconds,limit));}
    @GetMapping("/cameras")
    public Mono<ResponseEntity<Map<String,Object>>> cameras(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision){return endpoint.call(auth,actor,revision,false,()->{service.requireEnabled();return Map.of("items",locations.list());});}
    @PutMapping("/cameras/{camera}/location")
    public Mono<ResponseEntity<Map<String,Object>>> update(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable String camera,@RequestBody CameraLocations.Update body){return endpoint.call(auth,actor,revision,true,()->{service.requireEnabled();return locations.update(camera,body,actor);});}
    @GetMapping("/collections/{id}/map")
    public Mono<ResponseEntity<Map<String,Object>>> map(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@PathVariable UUID id){return endpoint.call(auth,actor,revision,false,()->service.collectionMap(id));}
    @GetMapping("/collections/{id}/package-manifest")
    public Mono<ResponseEntity<Map<String,Object>>> packageManifest(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long authRevision,@PathVariable UUID id,@RequestParam long revision,@RequestParam(defaultValue="1")int part){return endpoint.call(auth,actor,authRevision,false,()->{service.requireEnabled();return packages.prepare(id,revision,part,actor);});}
}
