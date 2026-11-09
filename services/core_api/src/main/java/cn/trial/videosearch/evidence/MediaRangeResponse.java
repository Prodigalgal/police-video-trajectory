package cn.trial.videosearch.evidence;

import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/** Shared bounded file transport; callers own authentication and source verification. */
final class MediaRangeResponse {
    private MediaRangeResponse() {}
    static ResponseEntity<Flux<DataBuffer>> read(Path path,String requested)throws IOException {
        long size=Files.size(path);
        if(size<=0)return ResponseEntity.notFound().build();
        long[] selected=range(requested,size);
        if(selected==null)return ResponseEntity.status(416).header(HttpHeaders.CONTENT_RANGE,"bytes */"+size).build();
        long start=selected[0],end=selected[1],length=end-start+1;
        Flux<DataBuffer> body=DataBufferUtils.readByteChannel(()->{
            var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
            channel.position(start);return channel;
        },new DefaultDataBufferFactory(),64*1024).transform(stream->DataBufferUtils.takeUntilByteCount(stream,length)).subscribeOn(Schedulers.boundedElastic());
        var response=ResponseEntity.status(requested==null?200:206).contentType(MediaType.valueOf("video/mp4"))
            .contentLength(length).header(HttpHeaders.ACCEPT_RANGES,"bytes").cacheControl(CacheControl.noStore());
        if(requested!=null)response.header(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+size);
        return response.body(body);
    }
    static long[] range(String header,long size){
        if(size<=0)return null;
        if(header==null)return new long[]{0,size-1};
        if(header.length()>100||!header.matches("bytes=\\d*-\\d*"))return null;
        String[] parts=header.substring(6).split("-",-1);
        if(parts[0].isEmpty()&&parts[1].isEmpty())return null;
        try{
            long start,end;
            if(parts[0].isEmpty()){
                long suffix=Long.parseLong(parts[1]);if(suffix<=0)return null;
                start=Math.max(0,size-suffix);end=size-1;
            }else{start=Long.parseLong(parts[0]);end=parts[1].isEmpty()?size-1:Math.min(Long.parseLong(parts[1]),size-1);}
            return start>=size||end<start?null:new long[]{start,end};
        }catch(NumberFormatException invalid){return null;}
    }
}
