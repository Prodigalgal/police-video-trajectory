package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** A bounded, immutable ZIP under job/generation; all entry hashes are checked outside transactions. */
@Service
public class ExportFiles {
    public record Artifact(Path path,long bytes,String sha256){}
    private record Entry(long bytes,String sha256){}
    private final Path root;
    public ExportFiles(@Value("${VIDEO_EXPORT_DIR:/evidence-exports}")String root){this.root=Path.of(root).toAbsolutePath().normalize();}
    private static InputRejected changed(){return new InputRejected(409,"export_artifact_changed");}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static String hash(InputStream input,long expected)throws IOException{
        var digest=sha();long size=0;byte[] buffer=new byte[1024*1024];int count;
        while((count=input.read(buffer))!=-1){size+=count;if(size>expected)throw changed();digest.update(buffer,0,count);}if(size!=expected)throw changed();return HexFormat.of().formatHex(digest.digest());
    }
    private static void add(Map<String,Entry> entries,String name,long bytes,String digest){
        if(name==null||name.contains("..")||name.startsWith("/")||name.contains("\\")||bytes<1||!digest.matches("[a-f0-9]{64}")||entries.putIfAbsent(name,new Entry(bytes,digest))!=null)throw changed();
    }
    private static void addText(Map<String,Entry> entries,String name,String text,String digest){if(!CanonicalJson.sha256(text).equals(digest))throw changed();add(entries,name,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,digest);}
    public Artifact verify(UUID id,long generation,String manifestPayload,String manifestSha,String expectedSha,long expectedBytes){
        if(generation<1||expectedSha==null||!expectedSha.matches("[a-f0-9]{64}")||expectedBytes<1||expectedBytes>ExportPlans.MAX_ARTIFACT)throw new IllegalArgumentException("Invalid export artifact");
        Path job=root.resolve(id.toString().replace("-","")),attempt=job.resolve(Long.toString(generation)),path=attempt.resolve("package.zip");
        try{
            if(Files.isSymbolicLink(root)||Files.isSymbolicLink(job)||Files.isSymbolicLink(attempt)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw changed();
            var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(before.size()!=expectedBytes)throw changed();
            var plan=ExportPlans.decode(manifestPayload);var expected=new LinkedHashMap<String,Entry>();
            addText(expected,"manifest.json",manifestPayload,manifestSha);String checksum=manifestSha+"  manifest.json\n";addText(expected,"manifest.sha256",checksum,CanonicalJson.sha256(checksum));
            for(var asset:ExportPlans.rows(plan.get("assets")))add(expected,(String)asset.get("filename"),((Number)asset.get("bytes")).longValue(),(String)asset.get("sha256"));
            for(var source:ExportPlans.rows(plan.get("media_sources")))addText(expected,(String)source.get("filename"),(String)source.get("manifest_payload"),(String)source.get("sha256"));
            try(var archive=new ZipFile(path.toFile())){
                if(archive.size()!=expected.size()||archive.size()>24)throw changed();var visited=new HashSet<String>();var iterator=archive.entries();
                while(iterator.hasMoreElements()){
                    var entry=iterator.nextElement();var required=expected.get(entry.getName());
                    if(required==null||!visited.add(entry.getName())||entry.isDirectory()||entry.getMethod()!=ZipEntry.STORED||entry.getSize()!=required.bytes()||entry.getCompressedSize()!=required.bytes())throw changed();
                    try(var input=archive.getInputStream(entry)){if(!hash(input,required.bytes()).equals(required.sha256()))throw changed();}
                }
            }
            try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var input=java.nio.channels.Channels.newInputStream(channel)){
                if(!hash(input,expectedBytes).equals(expectedSha))throw changed();
            }
            var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw changed();
            return new Artifact(path,expectedBytes,expectedSha);
        }catch(IOException error){throw new InputRejected(422,"export_artifact_unavailable");}
    }
}
