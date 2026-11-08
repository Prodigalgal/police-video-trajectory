package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/media-clips")
public class MediaClipController {
    private final MediaClips clips;
    private final SearchOrchestrator search;
    public MediaClipController(MediaClips clips,SearchOrchestrator search){this.clips=clips;this.search=search;}
    @GetMapping
    public Mono<ResponseEntity<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String auth,@RequestParam(name="camera_id")String camera,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){
        if(!search.authorized(auth))return Mono.just(ResponseEntity.status(401).body(Map.of("code","unauthorized")));
        return Mono.fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(clips.list(camera,limit,after))).subscribeOn(Schedulers.boundedElastic())
            .onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).body(Map.of("code",e.code))))
            .onErrorResume(IllegalArgumentException.class,e->Mono.just(ResponseEntity.badRequest().body(Map.of("code","invalid_media_request"))))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).body(Map.of("code","media_unavailable"))));
    }
    @GetMapping("/{id}/manifest")
    public Mono<ResponseEntity<byte[]>> manifest(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id){
        if(!search.authorized(auth))return Mono.just(failure(401,"unauthorized"));
        return Mono.fromCallable(()->{var asset=clips.verify(id,MediaClipFiles.Mode.MANIFEST,-1);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).header("X-Content-SHA256",asset.sha256()).body(asset.payload().getBytes(StandardCharsets.UTF_8));})
            .subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(failure(e.status,e.code)))
            .onErrorResume(Exception.class,e->Mono.just(failure(503,"media_unavailable")));
    }
    @GetMapping("/{id}/samples/{index}")
    public Mono<ResponseEntity<byte[]>> sample(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable int index){
        if(!search.authorized(auth))return Mono.just(failure(401,"unauthorized"));
        return Mono.fromCallable(()->{var image=clips.verify(id,MediaClipFiles.Mode.SAMPLE,index).sample();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).header("X-Content-SHA256",image.sha256()).body(image.bytes());})
            .subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(failure(e.status,e.code)))
            .onErrorResume(Exception.class,e->Mono.just(failure(503,"media_unavailable")));
    }
    @GetMapping("/{id}/video")
    public Mono<ResponseEntity<Flux<DataBuffer>>> video(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Range",required=false)String range,@PathVariable UUID id){
        if(!search.authorized(auth))return Mono.just(ResponseEntity.status(401).build());
        return Mono.fromCallable(()->{
            var asset=clips.verify(id,MediaClipFiles.Mode.VIDEO,-1);long size=asset.videoBytes();long[] selected=ArchiveController.parseRange(range,size);
            if(selected==null)return ResponseEntity.status(416).header(HttpHeaders.CONTENT_RANGE,"bytes */"+size).<Flux<DataBuffer>>build();
            long start=selected[0],length=selected[1]-start+1;
            Flux<DataBuffer> body=DataBufferUtils.readByteChannel(()->{SeekableByteChannel channel=Files.newByteChannel(asset.video(),Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));channel.position(start);return channel;},new DefaultDataBufferFactory(),64*1024)
                .transform(stream->DataBufferUtils.takeUntilByteCount(stream,length)).subscribeOn(Schedulers.boundedElastic());
            var response=ResponseEntity.status(range==null?200:206).cacheControl(CacheControl.noStore()).contentType(MediaType.valueOf("video/mp4")).contentLength(length).header(HttpHeaders.ACCEPT_RANGES,"bytes");
            if(range!=null)response.header(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+selected[1]+"/"+size);
            return response.body(body);
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(InputRejected.class,e->Mono.just(ResponseEntity.status(e.status).build()))
            .onErrorResume(Exception.class,e->Mono.just(ResponseEntity.status(503).build()));
    }
    private static ResponseEntity<byte[]> failure(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(("{\"code\":\""+code+"\"}").getBytes(StandardCharsets.UTF_8));}
}
