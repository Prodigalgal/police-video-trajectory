package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NearbyRecordsTest {
    @Test void unauthorizedReadersCannotTouchTheSource(){
        var records=mock(NearbyRecords.class);var scope=mock(SearchOrchestrator.class);
        var endpoint=new NearbyRecordsController(records,scope);
        assertEquals(401,endpoint.read(null,UUID.randomUUID(),1L,9).block().getStatusCode().value());verifyNoInteractions(records);
    }
    @Test void revisionConflictsAndUnboundedRequestsAreRejectedBeforeFileReads()throws Exception{
        var temporal=mock(TemporalEvidence.class);var images=mock(EvidenceFiles.class);var service=new NearbyRecords(mock(JdbcTemplate.class),temporal,images);UUID id=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->service.list(id,1L,18));
        when(temporal.anchor(id,1L)).thenThrow(new InputRejected(409,"observation_revision_conflict"));
        assertEquals(409,assertThrows(InputRejected.class,()->service.list(id,1L,9)).status);verifyNoInteractions(images);
    }
    @Test void legacyPixelsAreVerifiedWithoutInventingCaptureRegistrationOrASequence()throws Exception{
        var db=mock(JdbcTemplate.class);var temporal=mock(TemporalEvidence.class);var images=mock(EvidenceFiles.class);
        UUID frame=UUID.randomUUID(),observation=UUID.randomUUID();var values=new HashMap<String,Object>();
        values.put("frame_id",frame);values.put("task_id",UUID.randomUUID());values.put("camera_id","camera-1");values.put("source_type","rtsp");values.put("source_time_origin","unknown");values.put("received_at",Timestamp.from(Instant.parse("2026-11-09T00:00:00Z")));values.put("evidence_ref","evidence:"+frame.toString().replace("-",""));
        var source=new TemporalEvidence.Frame(values);var anchor=new TemporalEvidence.Anchor(observation,1,Map.of(),source);
        when(temporal.anchor(observation,1L)).thenReturn(anchor);when(temporal.frames(List.of(frame))).thenReturn(List.of(source));
        when(images.read(frame,source.reference())).thenReturn(new EvidenceFiles.FrameFile(new byte[]{1},1280,960,"a".repeat(64)));
        var reply=new NearbyRecords(db,temporal,images).list(observation,1L,1);
        assertEquals(false,reply.get("sequence_eligible"));assertEquals(false,reply.get("continuous_video_verified"));assertEquals("recorded_at",reply.get("time_basis"));
        var view=(Map<?,?>)((List<?>)reply.get("items")).getFirst();assertEquals("a".repeat(64),view.get("preview_sha256"));assertNull(view.get("registered_payload_sha256"));assertNull(view.get("relative_media_ms"));assertEquals(0L,view.get("record_offset_ms"));
    }
}
