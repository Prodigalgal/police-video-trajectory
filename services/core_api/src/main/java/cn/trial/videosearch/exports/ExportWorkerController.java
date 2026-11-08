package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.InputRejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/internal/v2/exports")
public class ExportWorkerController {
    private static final Logger LOG=LoggerFactory.getLogger(ExportWorkerController.class);
    private final ExportJobs jobs;private final ExportAssets assets;private final ExportArtifactLifecycle lifecycle;private final byte[] expected;
    public ExportWorkerController(ExportJobs jobs,ExportAssets assets,ExportArtifactLifecycle lifecycle,@Value("${VIDEO_WORKER_TOKEN:}")String token){this.jobs=jobs;this.assets=assets;this.lifecycle=lifecycle;this.expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
    private boolean authorized(String auth){return expected!=null&&auth!=null&&auth.length()<=1024&&MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8));}
    private static ResponseEntity<?> failure(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
    private Mono<ResponseEntity<?>> call(String auth,Supplier<Map<String,Object>> action){if(!authorized(auth))return Mono.just(failure(401,"unauthorized"));return Mono.<ResponseEntity<?>>fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.get())).subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(failure(e.status,e.code))).onErrorResume(IllegalArgumentException.class,e->Mono.just(failure(400,"invalid_export_request"))).onErrorResume(Exception.class,e->{LOG.warn("Export worker operation failed: {}",e.getClass().getSimpleName());return Mono.just(failure(503,"export_unavailable"));});}
    @PostMapping("/claim") public Mono<ResponseEntity<?>> claim(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ExportJobs.Claim body){return call(auth,()->jobs.claim(body));}
    @PostMapping("/{id}/renew") public Mono<ResponseEntity<?>> renew(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody ExportJobs.Lease body){return call(auth,()->jobs.renew(id,body));}
    @PostMapping("/{id}/complete") public Mono<ResponseEntity<?>> complete(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody ExportJobs.Complete body){return call(auth,()->jobs.complete(id,body));}
    @PostMapping("/{id}/fail") public Mono<ResponseEntity<?>> fail(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody ExportJobs.Fail body){return call(auth,()->jobs.fail(id,body));}
    @PostMapping("/reclaims/claim") public Mono<ResponseEntity<?>> reclaim(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody ExportArtifactLifecycle.Claim body){return call(auth,()->lifecycle.claim(body));}
    @PostMapping("/{id}/reclaims/{generation}/verify") public Mono<ResponseEntity<?>> verifyReclaim(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable long generation,@RequestBody ExportArtifactLifecycle.Lease body){return call(auth,()->lifecycle.verify(id,generation,body));}
    @PostMapping("/{id}/reclaims/{generation}/complete") public Mono<ResponseEntity<?>> completeReclaim(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable long generation,@RequestBody ExportArtifactLifecycle.Lease body){return call(auth,()->lifecycle.complete(id,generation,body));}
    @GetMapping("/{id}/assets/{key}") public Mono<ResponseEntity<Flux<DataBuffer>>> asset(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable String key,@RequestParam String worker,@RequestParam long generation){
        if(!authorized(auth))return Mono.just(ExportStreams.failure(401,"unauthorized"));
        return Mono.<ResponseEntity<Flux<DataBuffer>>>fromCallable(()->{
            var value=jobs.workerSnapshot(id,worker,generation);var plan=ExportPlans.decode((String)value.get("payload"));var selected=ExportPlans.rows(plan.get("assets")).stream().filter(asset->key.equals(asset.get("asset_key"))).findFirst().orElseThrow(()->new InputRejected(404,"export_asset_unavailable"));var content=assets.read(selected);
            Flux<DataBuffer> body=content.path()==null?Flux.just(new DefaultDataBufferFactory().wrap(content.bytes())):ExportStreams.file(content.path(),0,content.size());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(content.mediaType())).contentLength(content.size()).header("X-Content-SHA256",content.sha256()).body(body);
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(ExportStreams.failure(e.status,e.code))).onErrorResume(Exception.class,e->{LOG.warn("Export asset read failed export_id={} type={}",id,e.getClass().getSimpleName());return Mono.just(ExportStreams.failure(503,"export_asset_unavailable"));});
    }
}
