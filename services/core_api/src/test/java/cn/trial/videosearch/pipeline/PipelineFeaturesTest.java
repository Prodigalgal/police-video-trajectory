package cn.trial.videosearch.pipeline;

import cn.trial.videosearch.admin.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PipelineFeaturesTest {
    @Test void onlyInstalledPluginsAndExactParametersAreAccepted(){
        PipelineFeatureRegistry.validate("frame_recording","short_segment_v1",Map.of());
        for(int seconds:List.of(3,5,10))PipelineFeatureRegistry.validate("frame_recording","context_window_v1",Map.of("seconds",seconds));
        assertThrows(IllegalArgumentException.class,()->PipelineFeatureRegistry.validate("frame_recording","context_window_v1",Map.of("seconds",5.0)));
        assertThrows(IllegalArgumentException.class,()->PipelineFeatureRegistry.validate("frame_recording","short_segment_v1",Map.of("seconds",5)));
        assertThrows(AdminService.Rejected.class,()->PipelineFeatureRegistry.validate("frame_recording","uninstalled",Map.of()));
        assertEquals(false,PipelineFeatureRegistry.definition("frame_recording").get("default_enabled"));
    }
    @Test void staleWritesCannotCreateAnAuditOrCommandReceipt(){
        var db=mock(JdbcTemplate.class);var features=new PipelineFeatures(db);
        var command=new PipelineFeatures.Update(UUID.randomUUID(),1L,true,"short_segment_v1",Map.of());
        var rejected=assertThrows(AdminService.Rejected.class,()->features.update("frame_recording",command,"admin"));
        assertEquals(409,rejected.status);assertEquals("pipeline_feature_revision_conflict",rejected.code);
        verify(db,never()).update(startsWith("INSERT"),any(Object[].class));
    }
    @Test void workerAuthenticationFailsBeforeReadingTheConfiguration(){
        var features=mock(PipelineFeatures.class);var controller=new PipelineFeatureWorkerController(features,"contract-only");
        assertEquals(401,controller.get(null).block().getStatusCode().value());
        assertEquals(401,controller.get("Bearer wrong").block().getStatusCode().value());
        verifyNoInteractions(features);
    }
}
