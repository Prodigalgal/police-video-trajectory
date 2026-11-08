package cn.trial.videosearch.admin;

import cn.trial.videosearch.ingest.AnalysisQueue;
import cn.trial.videosearch.ingest.AnalysisQueuePolicy;
import cn.trial.videosearch.ingest.ModuleQueue;
import cn.trial.videosearch.ingest.PipelineOperations;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminServiceTest {
    private static ModuleQueue moduleQueue(){var modules=mock(ModuleQueue.class);when(modules.policy()).thenReturn(Map.of("active_limit",3));return modules;}
    @Test void overviewQueueDetailsRemainInsideAllowedCameraScope() {
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var queue=mock(AnalysisQueue.class);
        when(search.readableCameras()).thenReturn(Set.of("recording-01"));
        when(queue.pendingLimit()).thenReturn(20);when(queue.policy()).thenReturn(Map.of("active_limit",2));
        when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(invocation->{
            assertTrue(((String)invocation.getArgument(0)).contains("?"));
            assertArrayEquals(new Object[]{"recording-01"},(Object[])invocation.getRawArguments()[1]);
            return List.of(Map.of("count",1L,"total",1L,"enabled",0L,"retry_waiting",1L));
        });
        var result=new AdminService(db,search,queue,null,moduleQueue()).overview();
        assertEquals(1L,((Map<?,?>)result.get("queue_load")).get("retry_waiting"));
        assertEquals(Map.of("active_limit",2),result.get("queue_policy"));
        verify(queue,never()).load();
    }
    @Test void cameraAliasesStayBoundedAndKeepStableSearchNames() {
        var request=new AdminService.CameraUpdate("监控点位01",null,List.of("北门","北门"),true,"a".repeat(64));
        assertEquals(List.of("recording-01","北门","监控点位01"),AdminService.aliases("recording-01",request));
        assertThrows(IllegalArgumentException.class,()->AdminService.aliases("recording-01",new AdminService.CameraUpdate("name\ncommand",null,List.of(),true,"a".repeat(64))));
    }
    @Test void pauseStatePreventsRetryAndAnyEnqueue() {
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var operations=mock(PipelineOperations.class);
        when(search.readableCameras()).thenReturn(Set.of("recording-01"));
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("enabled",false,"camera_id","recording-01")));
        when(db.queryForObject(anyString(),eq(Boolean.class))).thenReturn(false);
        var service=new AdminService(db,search,new AnalysisQueue(db, 2000, new AnalysisQueuePolicy(2,5,5,300)),operations,moduleQueue());
        assertEquals("processing_paused",assertThrows(AdminService.Rejected.class,()->service.retry(UUID.randomUUID(),new AdminService.Retry(UUID.randomUUID(),1,"核验重试"),"admin")).code);
        verifyNoInteractions(operations);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void managementAuthenticationAndAdministratorCheckPrecedeOperations() {
        var service=mock(AdminService.class);var users=mock(UserService.class);var controller=new AdminController(service,users,"private-token");
        assertEquals(401,controller.overview("Bearer wrong","admin").block().getStatusCode().value());verifyNoInteractions(service,users);
        doThrow(new AdminService.Rejected(403,"admin_required")).when(users).requireAdmin("viewer");
        assertEquals(403,controller.overview("Bearer private-token","viewer").block().getStatusCode().value());verifyNoInteractions(service);
    }
}
