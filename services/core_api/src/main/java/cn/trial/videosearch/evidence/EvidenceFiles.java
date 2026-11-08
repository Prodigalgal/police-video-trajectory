package cn.trial.videosearch.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** One bounded reader for registered key frames and their actual dimensions. */
@Service
public class EvidenceFiles {
    public record FrameFile(byte[] bytes,int width,int height,String sha256) {}
    private static final int MAX_IMAGE=8*1024*1024;
    private final Path root;
    public EvidenceFiles(@Value("${VIDEO_EVIDENCE_DIR:/evidence}") String root) { this.root=Path.of(root).toAbsolutePath().normalize(); }
    public FrameFile read(UUID frame,String reference) throws IOException {
        String key=frame.toString().replace("-","");
        if(!("evidence:"+key).equals(reference)) return null;
        Path path=root.resolve(key+".jpg").normalize();
        if(!path.getParent().equals(root) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) return null;
        byte[] bytes;
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var input=Channels.newInputStream(channel)) { bytes=input.readNBytes(MAX_IMAGE+1); }
        if(bytes.length<3 || bytes.length>MAX_IMAGE || bytes[0]!=(byte)0xff || bytes[1]!=(byte)0xd8 || bytes[2]!=(byte)0xff) return null;
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) return null;
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>20000 || height>20000 || (long)width*height>50_000_000) return null;
                return new FrameFile(bytes,width,height,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } finally { reader.dispose(); }
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
