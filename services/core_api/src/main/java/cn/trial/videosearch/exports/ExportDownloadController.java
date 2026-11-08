package cn.trial.videosearch.exports;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.support.ActorAccess;
import org.slf4j.*;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class ExportDownloadController {
    private static final Logger LOG=LoggerFactory.getLogger(ExportDownloadController.class);
    private final ExportJobs jobs;private final ActorAccess access;
    public ExportDownloadController(ExportJobs jobs,ActorAccess access){this.jobs=jobs;this.access=access;}
    private Mono<Void> finish(ExportJobs.Transfer transfer,AtomicLong sent,String state){return Mono.fromRunnable(()->jobs.finishTransfer(transfer,sent.get(),state)).subscribeOn(Schedulers.boundedElastic()).retryWhen(Retry.backoff(2,Duration.ofMillis(200))).doOnError(error->LOG.warn("Export transfer journal could not finalize transfer {}",transfer.id())).then();}
    @GetMapping("/internal/exports/{id}/download")
    public Mono<ResponseEntity<Flux<DataBuffer>>> download(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Actor-Id",required=false)String actor,@RequestHeader(value="X-Actor-Revision",required=false)Long revision,@RequestHeader(value="Range",required=false)String range,@PathVariable UUID id){
        return Mono.<ResponseEntity<Flux<DataBuffer>>>fromCallable(()->{
            access.require(auth,actor,revision,false);var transfer=jobs.download(id,actor,range);long length=transfer.end()-transfer.start()+1;var sent=new AtomicLong();
            Flux<DataBuffer> body=Flux.usingWhen(Mono.just(transfer),value->ExportStreams.file(value.artifact().path(),value.start(),length).takeUntilOther(Mono.delay(Duration.ofMinutes(15)).flatMap(tick->Mono.error(new IllegalStateException("Export transfer deadline")))).doOnNext(buffer->sent.addAndGet(buffer.readableByteCount())).concatWith(Mono.defer(()->sent.get()==length?Mono.empty():Mono.error(new IllegalStateException("Export truncated")))),value->finish(value,sent,"server_complete"),(value,error)->finish(value,sent,"failed"),value->finish(value,sent,"interrupted"));
            var response=ResponseEntity.status(range==null?200:206).cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip")).contentLength(length).header(HttpHeaders.ACCEPT_RANGES,"bytes").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"evidence-"+id+".zip\"").header("X-Content-SHA256",transfer.artifact().sha256()).header("X-Transfer-Id",transfer.id().toString());
            if(range!=null)response.header(HttpHeaders.CONTENT_RANGE,"bytes "+transfer.start()+"-"+transfer.end()+"/"+transfer.artifact().bytes());return response.body(body);
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(ExportStreams.failure(e.status,e.code))).onErrorResume(AdminService.Rejected.class,e->Mono.just(ExportStreams.failure(e.status,e.code))).onErrorResume(Exception.class,e->{LOG.warn("Export download could not start",e);return Mono.just(ExportStreams.failure(503,"export_unavailable"));});
    }
}
