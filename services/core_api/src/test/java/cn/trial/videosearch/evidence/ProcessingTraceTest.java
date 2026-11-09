package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.ActorAccess;
import cn.trial.videosearch.admin.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProcessingTraceTest {
    @Test void frameAccessChecksCameraScopeBeforeReadingFiles(){
        var db=mock(JdbcTemplate.class);var scope=mock(SearchOrchestrator.class);var files=mock(EvidenceFiles.class);UUID frame=UUID.randomUUID();
        when(db.queryForList(contains("WITH anchor"),eq(frame),eq(frame))).thenReturn(List.of(Map.of("camera_id","foreign")));
        var trace=new ProcessingTrace(db,scope,files);
        assertEquals(404,assertThrows(InputRejected.class,()->trace.frame(frame)).status);verifyNoInteractions(files);
    }
    @Test void oldObservationVersionCannotReadANewerFrameTrace(){
        var db=mock(JdbcTemplate.class);var scope=mock(SearchOrchestrator.class);var files=mock(EvidenceFiles.class);UUID observation=UUID.randomUUID();
        when(db.queryForList(startsWith("SELECT frame_id,camera_id,record_version"),eq(observation))).thenReturn(List.of(Map.of("frame_id",UUID.randomUUID(),"camera_id","camera-1","record_version",2L)));
        when(scope.mayReadCamera("camera-1")).thenReturn(true);
        assertEquals(409,assertThrows(InputRejected.class,()->new ProcessingTrace(db,scope,files).observation(observation,1)).status);verifyNoInteractions(files);
    }
    @Test void adminImageRouteAuthenticatesBeforeAccessingSource(){
        var traces=mock(ProcessingTrace.class);var access=mock(ActorAccess.class);
        doThrow(new AdminService.Rejected(403,"admin_required")).when(access).require(any(),any(),any(),eq(true));
        var controller=new ProcessingTraceController(traces,null,access);
        assertEquals(403,controller.image("token","reader",1L,UUID.randomUUID(),"a".repeat(64)).block(Duration.ofSeconds(3)).getStatusCode().value());verifyNoInteractions(traces);
    }
    @Test void changedImageNeverBecomesAReplaySource()throws Exception{
        var db=mock(JdbcTemplate.class);var scope=mock(SearchOrchestrator.class);var files=mock(EvidenceFiles.class);UUID frame=UUID.randomUUID();
        var row=new HashMap<String,Object>();row.put("camera_id","camera-1");row.put("display_name","一号点位");row.put("payload_sha256","a".repeat(64));row.put("evidence_ref","reference");row.put("plan","{\"profile\":\"small-detector\",\"source_ref\":\"private\",\"credential_file\":\"secret\"}");
        when(db.queryForList(contains("WITH anchor"),eq(frame),eq(frame))).thenReturn(List.of(row));when(scope.mayReadCamera("camera-1")).thenReturn(true);
        when(files.read(frame,"reference")).thenReturn(new EvidenceFiles.FrameFile(new byte[]{1},1,1,"b".repeat(64)));
        when(db.queryForMap(startsWith("SELECT count(*) AS total"),eq(frame))).thenReturn(Map.of("total",0L,"submitted",0L));
        var result=new ProcessingTrace(db,scope,files).frame(frame);
        var manifest=cn.trial.videosearch.ingest.InferenceReceipts.parse((String)result.get("replay_manifest_json"));
        assertEquals(false,manifest.get("image_available"));assertEquals(false,manifest.get("request_available"));
        assertEquals(Map.of("profile","small-detector"),manifest.get("preprocess"));
        assertEquals("not_evaluated",((Map<?,?>)result.get("quality")).get("recognition_accuracy"));
    }
}
