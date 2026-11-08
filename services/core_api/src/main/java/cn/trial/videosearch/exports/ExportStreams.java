package cn.trial.videosearch.exports;

import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import cn.trial.videosearch.support.CanonicalJson;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.nio.file.*;
import java.util.Set;
import java.util.Map;
import java.nio.charset.StandardCharsets;

final class ExportStreams {
    private ExportStreams(){}
    static ResponseEntity<Flux<DataBuffer>> failure(int status,String code){
        byte[] json=CanonicalJson.encode(Map.of("code",code),1024).payload().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).contentLength(json.length)
            .body(Flux.defer(()->Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(json))));
    }
    static Flux<DataBuffer> file(Path path,long start,long length){
        return DataBufferUtils.readByteChannel(()->{var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));channel.position(start);return channel;},new DefaultDataBufferFactory(),64*1024)
            .transform(stream->DataBufferUtils.takeUntilByteCount(stream,length)).subscribeOn(Schedulers.boundedElastic()).doOnDiscard(DataBuffer.class,DataBufferUtils::release);
    }
}
