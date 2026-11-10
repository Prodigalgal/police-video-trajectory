package cn.trial.videosearch.evidence;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;

/** Reuses a verified immutable asset only while its inode/ctime/mtime/size match. */
final class RecordingContentVerifier {
    private static final int MAX_ENTRIES=128;
    private static final long VALID_NANOS=30_000_000_000L;
    private final Map<Path,Verified> verified=new LinkedHashMap<>(16,.75f,true);
    private record Stamp(Object fileKey,long size,FileTime modified,FileTime changed){}
    private record Verified(Stamp stamp,String sha256,long at){}

    void verify(Path path,long expectedBytes,String expectedSha)throws IOException{
        var before=stamp(path);
        if(before.size()!=expectedBytes)throw changed();
        Verified previous;
        synchronized(verified){previous=verified.get(path);}
        if(before.changed()!=null&&before.fileKey()!=null&&previous!=null&&before.equals(previous.stamp())
            &&expectedSha.equals(previous.sha256())&&System.nanoTime()-previous.at()<VALID_NANOS)return;
        String actual=hash(path,expectedBytes);
        if(!before.equals(stamp(path))||!expectedSha.equals(actual))throw changed();
        if(before.changed()!=null&&before.fileKey()!=null){
            synchronized(verified){
                verified.put(path,new Verified(before,actual,System.nanoTime()));
                while(verified.size()>MAX_ENTRIES)verified.remove(verified.keySet().iterator().next());
            }
        }
    }
    private static Stamp stamp(Path path)throws IOException{
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("Recording asset unavailable");
        // Unsupported filesystems always perform the full hash; mtime alone is
        // insufficient because a same-size rewrite can restore its old mtime.
        FileTime changed=path.getFileSystem().supportedFileAttributeViews().contains("unix")
            ?(FileTime)Files.getAttribute(path,"unix:ctime",LinkOption.NOFOLLOW_LINKS):null;
        return new Stamp(attributes.fileKey(),attributes.size(),attributes.lastModifiedTime(),changed);
    }
    private static String hash(Path path,long expectedBytes)throws IOException{
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        long count=0;
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var input=Channels.newInputStream(channel)){
            if(channel.size()!=expectedBytes)throw changed();
            byte[] buffer=new byte[1024*1024];int length;
            while((length=input.read(buffer))!=-1){count+=length;if(count>expectedBytes)throw changed();digest.update(buffer,0,length);}
        }
        if(count!=expectedBytes)throw changed();
        return HexFormat.of().formatHex(digest.digest());
    }
    private static InputRejected changed(){return new InputRejected(409,"recording_pixels_changed");}
}
