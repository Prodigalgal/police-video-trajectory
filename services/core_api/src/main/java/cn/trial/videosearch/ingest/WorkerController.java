package cn.trial.videosearch.ingest;

import cn.trial.videosearch.evidence.AnalysisInputs;
import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.evidence.MediaClips;
import cn.trial.videosearch.evidence.MediaClipFiles;
import cn.trial.videosearch.evidence.FrameRecordings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Internal worker API protected by a dedicated service token. */
@RestController
public class WorkerController {
    private final TaskLeaseService leases;
    private final ObservationStore observations;
    private final AnalysisQueue analysis;
    private final TargetPipeline targets;
    private final ModuleQueue modules;
    private final AnalysisInputs inputs;
    private final MediaClips clips;
    private final FrameRecordings recordings;
    private final byte[] expected;
    public record ClaimRequest(String workerId, String sourceType, int leaseSeconds) {}
    public record RenewRequest(String workerId, long generation, int leaseSeconds) {}
    public record FinishRequest(String workerId, long generation, boolean completed) {}
    public record AnalysisClaimRequest(String workerId, int leaseSeconds) {}
    public record AnalysisFailure(String workerId, long generation, String errorCode) {}
    public record ClaimsControl(Boolean enabled, String actor) {}
    public record ModuleClaim(String workerId,int leaseSeconds,String moduleKey){}

    public WorkerController(TaskLeaseService leases, ObservationStore observations, AnalysisQueue analysis, TargetPipeline targets, ModuleQueue modules, AnalysisInputs inputs, MediaClips clips, FrameRecordings recordings, @Value("${VIDEO_WORKER_TOKEN:}") String token) {
        if (token.isBlank()) throw new IllegalArgumentException("worker token must be configured");
        this.leases = leases;
        this.observations = observations;
        this.analysis = analysis;
        this.targets = targets;
        this.modules = modules;
        this.inputs = inputs;
        this.clips = clips;
        this.recordings = recordings;
        expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    private Mono<ResponseEntity<Map<String, Object>>> call(String auth, Supplier<ResponseEntity<Map<String, Object>>> operation) {
        if (!authorized(auth))
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "未授权访问")));
        return Mono.fromCallable(operation::get).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
                .onErrorResume(ModuleResultSource.Invalid.class,e->Mono.just(ResponseEntity.status(422).body(Map.of("code","invalid_module_source"))))
                .onErrorResume(ModuleQueue.CapacityExceeded.class,e->Mono.just(ResponseEntity.status(429).body(Map.of("code","module_queue_full"))))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest().body(Map.of("detail", "请求无效"))))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "任务租约或记录冲突"))))
                .onErrorResume(Exception.class, e -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", "后台服务暂不可用"))));
    }

    private boolean authorized(String auth){return auth!=null&&auth.length()<=1024&&MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8));}

    @PostMapping("/internal/v1/frame-recordings/register")
    public Mono<ResponseEntity<Map<String,Object>>> registerRecording(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody FrameRecordings.Register request){return call(auth,()->ResponseEntity.ok(recordings.register(request)));}
    @GetMapping("/internal/v1/frame-recordings/{frameId}/manifest")
    public Mono<ResponseEntity<Map<String,Object>>> recordingManifest(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID frameId){
        return call(auth,()->{var asset=recordings.playback(frameId,false).orElseThrow(()->new InputRejected(404,"recording_unavailable"));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("manifest",asset.manifest(),"manifest_sha256",asset.manifestSha256(),"seek_offset_ms",asset.seekOffsetMs()));});
    }

    @PostMapping("/internal/v1/media-clips/register")
    public Mono<ResponseEntity<Map<String,Object>>> registerClip(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody MediaClips.Register request){return call(auth,()->ResponseEntity.ok(clips.register(request)));}
    @GetMapping("/internal/v1/media-clips/{id}/manifest")
    public Mono<ResponseEntity<Map<String,Object>>> clipManifest(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id){
        return call(auth,()->{var asset=clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1,false);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("manifest",asset.manifest(),"manifest_sha256",asset.sha256()));});
    }
    @GetMapping("/internal/v1/media-clips/{id}/samples/{index}")
    public Mono<ResponseEntity<byte[]>> clipSample(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable int index){
        if(!authorized(auth))return Mono.just(inputFailure(401,"unauthorized"));
        return Mono.fromCallable(()->{var image=clips.verify(id,MediaClipFiles.Mode.SAMPLE,index,false).sample();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).header("X-Content-SHA256",image.sha256()).body(image.bytes());})
            .subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(inputFailure(e.status,e.code)))
            .onErrorResume(Exception.class,e->Mono.just(inputFailure(503,"media_unavailable")));
    }

    @GetMapping("/internal/v1/analysis-inputs/{id}/manifest")
    public Mono<ResponseEntity<Map<String,Object>>> inputManifest(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id){
        return call(auth,()->{var verified=inputs.verify(id,null);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("manifest",verified.manifest(),"manifest_sha256",verified.sha256()));});
    }
    @GetMapping("/internal/v1/analysis-inputs/{id}/frames/{frame}/image")
    public Mono<ResponseEntity<byte[]>> inputImage(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable UUID frame){
        if(!authorized(auth))return Mono.just(inputFailure(401,"unauthorized"));
        return Mono.fromCallable(()->{
            var verified=inputs.verify(id,frame);var image=verified.image();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).header("X-Content-SHA256",image.sha256()).body(image.bytes());
        }).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(inputFailure(e.status,e.code)))
            .onErrorResume(Exception.class,e->Mono.just(inputFailure(503,"analysis_inputs_unavailable")));
    }
    private static ResponseEntity<byte[]> inputFailure(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(("{\"code\":\""+code+"\"}").getBytes(StandardCharsets.UTF_8));}

    @PostMapping("/internal/v1/tasks/claim")
    public Mono<ResponseEntity<Map<String, Object>>> claim(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody ClaimRequest body) {
        return call(auth, () -> {
            var lease = leases.claim(body.workerId(), body.sourceType(), body.leaseSeconds());
            return lease.isPresent() ? ResponseEntity.ok(Map.of("task", lease.get())) : ResponseEntity.ok(Map.of("task_available", false));
        });
    }

    @PostMapping("/internal/v1/tasks/{taskId}/renew")
    public Mono<ResponseEntity<Map<String, Object>>> renew(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID taskId, @RequestBody RenewRequest body) {
        return call(auth, () -> leases.renew(taskId, body.workerId(), body.generation(), body.leaseSeconds())
                .<ResponseEntity<Map<String, Object>>>map(lease -> ResponseEntity.ok(Map.of("lease", lease)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "租约已失效"))));
    }

    @PostMapping("/internal/v1/tasks/{taskId}/finish")
    public Mono<ResponseEntity<Map<String, Object>>> finish(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID taskId, @RequestBody FinishRequest body) {
        return call(auth, () -> leases.finish(taskId, body.workerId(), body.generation(), body.completed())
                ? ResponseEntity.ok(Map.of("finished", true)) : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "租约已失效")));
    }

    @PostMapping("/internal/v1/frames")
    public Mono<ResponseEntity<Map<String, Object>>> frame(@RequestHeader(value="Authorization", required=false) String auth,
                                                            @RequestBody ObservationStore.Frame body) {
        return call(auth, () -> ResponseEntity.ok(Map.of("objects", observations.save(body))));
    }

    @PostMapping("/internal/v1/frames/enqueue")
    public Mono<ResponseEntity<Map<String, Object>>> enqueue(@RequestHeader(value="Authorization", required=false) String auth,
                                                              @RequestBody AnalysisQueue.Submission body) {
        return call(auth, () -> ResponseEntity.ok(Map.of("jobId", analysis.enqueue(body))));
    }

    @PostMapping("/internal/v1/analysis/claim")
    public Mono<ResponseEntity<Map<String, Object>>> claimAnalysis(@RequestHeader(value="Authorization", required=false) String auth,
                                                                    @RequestBody AnalysisClaimRequest body) {
        return call(auth, () -> analysis.claim(body.workerId(), body.leaseSeconds())
                .<ResponseEntity<Map<String, Object>>>map(job -> ResponseEntity.ok(Map.of("job", job)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("job_available", false))));
    }

    @PostMapping("/internal/v1/analysis/{jobId}/renew")
    public Mono<ResponseEntity<Map<String, Object>>> renewAnalysis(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID jobId, @RequestBody RenewRequest body) {
        return call(auth, () -> analysis.renew(jobId, body.workerId(), body.generation(), body.leaseSeconds())
                ? ResponseEntity.ok(Map.of("renewed", true)) : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "租约已失效")));
    }

    @PostMapping("/internal/v1/analysis/{jobId}/fail")
    public Mono<ResponseEntity<Map<String, Object>>> failAnalysis(@RequestHeader(value="Authorization", required=false) String auth,
            @PathVariable UUID jobId, @RequestBody AnalysisFailure body) {
        return call(auth, () -> analysis.fail(jobId, body.workerId(), body.generation(), body.errorCode())
                ? ResponseEntity.ok(Map.of("recorded", true)) : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "租约已失效")));
    }

    @PostMapping("/internal/v1/analysis/complete")
    public Mono<ResponseEntity<Map<String, Object>>> completeAnalysis(@RequestHeader(value="Authorization", required=false) String auth,
                                                                       @RequestBody ObservationStore.AnalysisResult body) {
        return call(auth, () -> ResponseEntity.ok(Map.of("objects", observations.complete(body))));
    }

    @PostMapping("/internal/v2/captures/register")
    public Mono<ResponseEntity<Map<String,Object>>> registerCapture(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody TargetPipeline.Capture body) {
        return call(auth,()->ResponseEntity.ok(Map.of("capture",targets.register(body))));
    }

    @PostMapping("/internal/v2/captures/enqueue")
    public Mono<ResponseEntity<Map<String,Object>>> activateCapture(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody TargetPipeline.Activation body) {
        return call(auth,()->targets.activate(body)
                .<ResponseEntity<Map<String,Object>>>map(id->ResponseEntity.ok(Map.of("jobId",id,"frameId",body.frameId(),"payloadSha256",body.payloadSha256(),"queued",true)))
                .orElseGet(()->ResponseEntity.accepted().body(Map.of("frameId",body.frameId(),"payloadSha256",body.payloadSha256(),"queued",false))));
    }

    @PostMapping("/internal/v2/analysis/claim")
    public Mono<ResponseEntity<Map<String,Object>>> claimTargetAnalysis(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody AnalysisClaimRequest body) {
        return call(auth,()->targets.claim(body.workerId(),body.leaseSeconds())
                .<ResponseEntity<Map<String,Object>>>map(job->ResponseEntity.ok(Map.of("job",job)))
                .orElseGet(()->ResponseEntity.ok(Map.of("job_available",false))));
    }

    @PostMapping("/internal/v2/analysis/complete")
    public Mono<ResponseEntity<Map<String,Object>>> completeTargetAnalysis(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody TargetPipeline.Completion body) {
        return call(auth,()->ResponseEntity.ok(Map.of("objects",targets.complete(body))));
    }

    @PostMapping("/internal/v2/pipeline/status")
    public Mono<ResponseEntity<Map<String,Object>>> pipelineStatus(@RequestHeader(value="Authorization",required=false) String auth) {
        return call(auth,()->ResponseEntity.ok(targets.status()));
    }

    @PostMapping("/internal/v2/analysis/control")
    public Mono<ResponseEntity<Map<String,Object>>> analysisControl(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestBody ClaimsControl body) {
        return call(auth,()->ResponseEntity.ok(Map.of("claims_enabled",targets.controlClaims(body.enabled(),body.actor()))));
    }

    @PostMapping("/internal/v1/modules/claim")
    public Mono<ResponseEntity<Map<String,Object>>> claimModule(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody ModuleClaim body){
        return call(auth,()->modules.claim(body.workerId(),body.leaseSeconds(),body.moduleKey())
                .<ResponseEntity<Map<String,Object>>>map(job->ResponseEntity.ok(Map.of("job",job)))
                .orElseGet(()->ResponseEntity.ok(Map.of("job_available",false))));
    }
    @PostMapping("/internal/v1/modules/{jobId}/renew")
    public Mono<ResponseEntity<Map<String,Object>>> renewModule(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID jobId,@RequestBody RenewRequest body){
        return call(auth,()->modules.renew(jobId,body.workerId(),body.generation(),body.leaseSeconds())
                ?ResponseEntity.ok(Map.of("renewed",true)):ResponseEntity.status(409).body(Map.of("code","module_lease_expired")));
    }
    @PostMapping("/internal/v1/modules/{jobId}/fail")
    public Mono<ResponseEntity<Map<String,Object>>> failModule(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID jobId,@RequestBody AnalysisFailure body){
        return call(auth,()->modules.fail(jobId,body.workerId(),body.generation(),body.errorCode())
                ?ResponseEntity.ok(Map.of("recorded",true)):ResponseEntity.status(409).body(Map.of("code","module_lease_expired")));
    }
    @PostMapping("/internal/v1/modules/complete")
    public Mono<ResponseEntity<Map<String,Object>>> completeModule(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody ModuleQueue.Completion body){
        return call(auth,()->ResponseEntity.ok(modules.complete(body)));
    }
}
