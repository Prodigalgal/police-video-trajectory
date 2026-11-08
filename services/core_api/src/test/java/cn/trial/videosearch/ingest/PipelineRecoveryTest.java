package cn.trial.videosearch.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PipelineRecoveryTest {
    @TempDir Path temporary;
    @Test void verifiesOriginalCameraBytesAndRejectsChangedOrMissingEvidence() throws Exception {
        Path source=Path.of("../../runtime/file-upload-real-points/recording-01.jpg");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source),"private real-camera fixture required");
        UUID id=UUID.randomUUID();
        Files.copy(source,temporary.resolve(id.toString().replace("-","")+".jpg"));
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var evidence=new CaptureEvidence(temporary.toString());
        evidence.verify(id,digest);
        assertEquals("evidence_invalid",assertThrows(CaptureEvidence.Fault.class,()->evidence.verify(id,"0".repeat(64))).code());
        assertEquals("evidence_missing",assertThrows(CaptureEvidence.Fault.class,()->evidence.verify(UUID.randomUUID(),digest)).code());
    }
    @Test void rejectsMalformedRecoveryAndUnboundedPages() {
        var operations=new PipelineOperations(null,null,null,temporary.toString());
        assertThrows(IllegalArgumentException.class,()->PipelineOperations.validate(new PipelineOperations.Recovery(null,1,"operator","reason")));
        assertThrows(IllegalArgumentException.class,()->PipelineOperations.validate(new PipelineOperations.Recovery(UUID.randomUUID(),-1,"operator","reason")));
        assertThrows(IllegalArgumentException.class,()->PipelineOperations.validate(new PipelineOperations.Recovery(UUID.randomUUID(),1,"operator","\nreason")));
        assertThrows(IllegalArgumentException.class,()->operations.jobs("all",null,20,null));
        assertThrows(IllegalArgumentException.class,()->operations.jobs("failed",null,101,null));
        assertThrows(IllegalArgumentException.class,()->operations.quarantined(0,null));
    }
    @Test void cannotResetCompletedOrStaleGeneration() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        var evidence=mock(CaptureEvidence.class);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(),List.of(Map.of(
                "state","completed","analysis_state","completed","lease_generation",2L)));
        var operations=new PipelineOperations(db,new AnalysisQueue(db, 10, new AnalysisQueuePolicy(2,5,5,300)),evidence,temporary.toString());
        var error=assertThrows(PipelineOperations.Rejected.class,()->operations.retry(UUID.randomUUID(),
                new PipelineOperations.Recovery(UUID.randomUUID(),2,"operator","verify recovery")));
        assertEquals("state_revision_conflict",error.code);
        verifyNoInteractions(evidence);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void authenticationPrecedesOperations() {
        var operations=mock(PipelineOperations.class);
        var controller=new PipelineOperationsController(operations,null,"internal-token");
        assertEquals(401,controller.health(null).block(Duration.ofSeconds(5)).getStatusCode().value());
        assertEquals(401,controller.health("Bearer incorrect").block(Duration.ofSeconds(5)).getStatusCode().value());
        verifyNoInteractions(operations);
    }
}
