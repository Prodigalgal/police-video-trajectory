package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordingContentVerifierTest {
    @TempDir Path root;
    @Test void sameSizeRewriteCannotReuseValidationEvenIfMtimeIsRestored()throws Exception{
        Path path=root.resolve("byte-contract");Files.writeString(path,"first-value");
        var verifier=new RecordingContentVerifier();String expected=CanonicalJson.sha256("first-value");
        verifier.verify(path,11,expected);verifier.verify(path,11,expected);
        var modified=Files.getLastModifiedTime(path);
        Files.writeString(path,"other-value");Files.setLastModifiedTime(path,modified);
        assertEquals("recording_pixels_changed",assertThrows(InputRejected.class,()->verifier.verify(path,11,expected)).code);
    }
    @Test void replacementRemovalAndWrongExpectedDigestDoNotInheritAValidResult()throws Exception{
        Path path=root.resolve("byte-contract");Files.writeString(path,"first-value");
        var verifier=new RecordingContentVerifier();String expected=CanonicalJson.sha256("first-value");verifier.verify(path,11,expected);
        assertThrows(InputRejected.class,()->verifier.verify(path,11,"a".repeat(64)));
        Files.delete(path);Files.writeString(path,"other-value");
        assertThrows(InputRejected.class,()->verifier.verify(path,11,expected));
        Files.delete(path);assertThrows(java.io.IOException.class,()->verifier.verify(path,11,expected));
    }
}
