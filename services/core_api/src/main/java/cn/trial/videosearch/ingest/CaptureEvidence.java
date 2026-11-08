package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Stable fault categories; never return local paths or image bytes to callers. */
@Component
public class CaptureEvidence {
    private final Path root;
    public static class Fault extends IllegalStateException {
        private final String code;
        public Fault(String code) { super("capture evidence unavailable"); this.code=code; }
        public String code() { return code; }
    }
    public CaptureEvidence(@Value("${VIDEO_EVIDENCE_DIR:/evidence}") String root) {
        this.root=Path.of(root).toAbsolutePath().normalize();
    }
    public void verify(UUID frameId,String digest) {
        Path file=root.resolve(frameId.toString().replace("-","")+".jpg");
        try {
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) throw new Fault("evidence_missing");
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) ||
                    Files.size(file)<1 || Files.size(file)>8*1024*1024) throw new Fault("evidence_invalid");
            byte[] content;
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { content=input.readNBytes(8*1024*1024+1); }
            if(content.length>8*1024*1024 || (digest!=null && !HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)).equals(digest))) throw new Fault("evidence_invalid");
        } catch(java.io.IOException error) { throw new Fault("evidence_unreadable"); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
