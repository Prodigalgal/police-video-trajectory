package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceFilesTest {
    @TempDir Path root;
    @Test void mismatchedReferencesAndMissingFilesFailClosed() throws Exception {
        var files=new EvidenceFiles(root.toString());var id=UUID.randomUUID();
        assertNull(files.read(id,"evidence:../../other"));assertNull(files.read(id,"evidence:"+id.toString().replace("-","")));
    }
    @Test void oversizedAndNonJpegFilesCannotBeServed() throws Exception {
        var files=new EvidenceFiles(root.toString());var id=UUID.randomUUID();var key=id.toString().replace("-","");var path=root.resolve(key+".jpg");
        Files.write(path,new byte[]{1,2,3});assertNull(files.read(id,"evidence:"+key));
        byte[] oversized=new byte[8*1024*1024+1];oversized[0]=(byte)0xff;oversized[1]=(byte)0xd8;oversized[2]=(byte)0xff;
        Files.write(path,oversized);assertNull(files.read(id,"evidence:"+key));
    }
}
