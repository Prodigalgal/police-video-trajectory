package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.ActorAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecordingReviewTest {
    @Test void disabledAndForeignCameraDoNotReadTheDatabase(){
        var db=mock(JdbcTemplate.class);var scope=mock(SearchOrchestrator.class);when(scope.readableCameras()).thenReturn(Set.of("camera-1"));
        var disabled=new RecordingReviewQueries(db,scope,mock(FrameRecordings.class),mock(EvidenceFiles.class),false);
        assertEquals("recording_review_disabled",assertThrows(InputRejected.class,()->disabled.list(null,20,null)).code);
        var active=new RecordingReviewQueries(db,scope,mock(FrameRecordings.class),mock(EvidenceFiles.class),true);
        assertEquals(404,assertThrows(InputRejected.class,()->active.list("camera-2",20,null)).status);
        assertThrows(IllegalArgumentException.class,()->active.list(null,51,null));verifyNoInteractions(db);
    }
    @Test void unknownCursorIsRejectedInsteadOfReturningAnAmbiguousEmptyPage(){
        var db=mock(JdbcTemplate.class);var scope=mock(SearchOrchestrator.class);when(scope.readableCameras()).thenReturn(Set.of("camera-1"));
        var query=new RecordingReviewQueries(db,scope,mock(FrameRecordings.class),mock(EvidenceFiles.class),true);
        assertEquals("recording_cursor_invalid",assertThrows(InputRejected.class,()->query.list(null,20,UUID.randomUUID())).code);
    }
    @Test void pendingCaptureProjectionDoesNotInventSemanticCompletion(){
        var row=new HashMap<String,Object>();row.put("frame_id",UUID.randomUUID());row.put("camera_id","camera-1");row.put("display_name","一号点位");row.put("source_type","rtsp");row.put("registered_at",Timestamp.from(Instant.parse("2026-11-09T00:00:00Z")));row.put("payload_sha256","a".repeat(64));row.put("target_count",2);row.put("segment_id",UUID.randomUUID());row.put("seek_offset_ms",1250L);row.put("manifest_sha256","b".repeat(64));row.put("duration_us",5000000L);row.put("video_bytes",1234L);
        var view=RecordingReviewQueries.view(row);assertEquals("held",view.get("analysis_handoff"));assertFalse(view.containsKey("description"));
        row.put("admission_requested",true);assertEquals("pending",RecordingReviewQueries.view(row).get("analysis_handoff"));
        row.put("enqueued_at",Timestamp.from(Instant.now()));assertEquals("submitted",RecordingReviewQueries.view(row).get("analysis_handoff"));
        var video=(Map<?,?>)view.get("video");assertEquals(1250L,video.get("seek_offset_ms"));assertEquals(5000L,video.get("duration_ms"));
    }
    @Test void binaryRoutesAuthorizeBeforeAccessingAnySource(){
        var queries=mock(RecordingReviewQueries.class);var access=mock(ActorAccess.class);var controller=new RecordingReviewController(queries,null,access);
        doThrow(new AdminService.Rejected(403,"admin_required")).when(access).require(any(),any(),any(),eq(true));
        assertEquals(403,controller.video("token","reader",1L,null,UUID.randomUUID()).block(Duration.ofSeconds(3)).getStatusCode().value());verifyNoInteractions(queries);
    }
    @Test void sharedRangeTransportStreamsOnlyTheRequestedBytes(@TempDir Path folder)throws Exception{
        Path path=folder.resolve("transport.bin");Files.write(path,new byte[]{1,2,3,4,5,6});var response=MediaRangeResponse.read(path,"bytes=-2");
        assertEquals(206,response.getStatusCode().value());assertEquals("bytes 4-5/6",response.getHeaders().getFirst("Content-Range"));
        var joined=DataBufferUtils.join(Objects.requireNonNull(response.getBody())).block(Duration.ofSeconds(3));assertNotNull(joined);
        try{byte[] bytes=new byte[joined.readableByteCount()];joined.read(bytes);assertArrayEquals(new byte[]{5,6},bytes);}finally{DataBufferUtils.release(joined);}
        assertEquals(416,MediaRangeResponse.read(path,"bytes=6-").getStatusCode().value());assertEquals(416,MediaRangeResponse.read(path,"bytes=0-1,3-4").getStatusCode().value());
    }
}
