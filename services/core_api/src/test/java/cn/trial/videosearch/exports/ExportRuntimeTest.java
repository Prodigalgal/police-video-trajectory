package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.pipeline.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExportRuntimeTest {
    private PipelineFeatures features(boolean installed,boolean enabled){
        var value=mock(PipelineFeatures.class);when(value.installed(PipelineFeatureRegistry.EXPORT)).thenReturn(installed);
        when(value.state(eq(PipelineFeatureRegistry.EXPORT),anyBoolean())).thenReturn(new PipelineFeatures.State(enabled,installed,"bound_video_package_v1",3));return value;
    }
    @Test void installationIntentAndRecentConsumerAreIndependent(){
        var db=mock(JdbcTemplate.class);var runtime=new ExportRuntime(db,features(true,true));
        var offline=runtime.status();assertEquals(true,offline.get("enabled"));assertEquals(false,offline.get("online"));assertEquals(false,offline.get("can_create"));
        when(db.queryForList(startsWith("SELECT version FROM evidence_export_consumers"))).thenReturn(List.of(Map.of("version","0.35.0")));
        assertEquals(true,runtime.status().get("can_create"));
        var disabled=new ExportRuntime(db,features(true,false));assertEquals(true,disabled.status().get("online"));assertEquals(false,disabled.status().get("can_create"));
        var uninstalled=new ExportRuntime(db,features(false,true));assertEquals(false,uninstalled.status().get("installed"));assertEquals(false,uninstalled.status().get("can_create"));
    }
    @Test void closedOrUninstalledModulesNeverGrantProductionAndLocksAreRequested(){
        var db=mock(JdbcTemplate.class);var configuration=features(true,false);var runtime=new ExportRuntime(db,configuration);
        assertEquals("export_module_disabled",assertThrows(InputRejected.class,()->runtime.requireProducing(true)).code);
        verify(configuration).state(PipelineFeatureRegistry.EXPORT,true);verifyNoInteractions(db);
        assertEquals("exports_disabled",assertThrows(InputRejected.class,()->new ExportRuntime(db,features(false,false)).requireInstalled()).code);
    }
    @Test void capabilityAndHeartbeatBoundariesRejectInvalidWorkersBeforeWrites(){
        var db=mock(JdbcTemplate.class);var runtime=new ExportRuntime(db,features(true,false));
        for(var body:List.of(new ExportRuntime.Heartbeat("worker",List.of("unknown"),1024L*1024*1024,"0.35.0"),
                new ExportRuntime.Heartbeat("worker",List.of(ExportRuntime.CONTRACT,ExportRuntime.CONTRACT),1024L*1024*1024,"0.35.0"),
                new ExportRuntime.Heartbeat("worker",List.of(ExportRuntime.CONTRACT),1,"0.35.0"),
                new ExportRuntime.Heartbeat("worker",List.of(ExportRuntime.CONTRACT),1024L*1024*1024,"private-version"))){
            assertThrows(IllegalArgumentException.class,()->runtime.heartbeat(body));
        }
        verifyNoInteractions(db);
        when(db.queryForObject(startsWith("SELECT EXISTS"),eq(Boolean.class),eq(ExportRuntime.LARGE_NATIVE))).thenReturn(false);
        assertEquals("export_consumer_unavailable",assertThrows(InputRejected.class,()->runtime.requireConsumer(true)).code);
        var old=Map.<String,Object>of("assets",List.of(Map.of("kind","native","bytes",16L*1024*1024)));
        var large=Map.<String,Object>of("assets",List.of(Map.of("kind","native","bytes",16L*1024*1024+1)));
        assertFalse(ExportRuntime.needsLargeNative(old));assertTrue(ExportRuntime.needsLargeNative(large));
    }
    @Test void disabledCreationAndClaimsAvoidAssetIOWhileMaintenanceCanRemainInstalled()throws Exception{
        var db=mock(JdbcTemplate.class);var plans=mock(ExportPlans.class);var files=mock(ExportFiles.class);var users=mock(cn.trial.videosearch.admin.UserService.class);var lifecycle=mock(ExportArtifactLifecycle.class);
        var runtime=mock(ExportRuntime.class);doThrow(new InputRejected(409,"export_module_disabled")).when(runtime).requireProducing(false);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(invocation->new SimpleTransactionStatus());
        var jobs=new ExportJobs(db,plans,files,users,lifecycle,manager,runtime);
        assertEquals("export_module_disabled",assertThrows(InputRejected.class,()->jobs.create(new ExportJobs.Create(UUID.randomUUID(),UUID.randomUUID(),1,1,true,false),"admin",1)).code);
        assertEquals(false,jobs.claim(new ExportJobs.Claim("consumer",List.of(ExportRuntime.CONTRACT))).get("job_available"));
        verifyNoInteractions(plans,files,users,lifecycle);verify(runtime).producing(true);
    }
    @Test void databaseSourceFenceRejectsChangedBindingsWithoutFileIO(){
        var db=mock(JdbcTemplate.class);var images=mock(cn.trial.videosearch.evidence.EvidenceFiles.class);
        var recordings=mock(cn.trial.videosearch.evidence.FrameRecordings.class);var clips=mock(cn.trial.videosearch.evidence.MediaClips.class);
        var sessions=mock(cn.trial.videosearch.evidence.SessionMediaInputs.class);var assets=new ExportAssets(db,images,recordings,clips,sessions);
        UUID frame=UUID.randomUUID();String identity=UUID.randomUUID().toString();
        var asset=Map.<String,Object>of("kind","native","identity",identity,"manifest_sha256","a".repeat(64));
        var facts=new LinkedHashMap<String,Object>(Map.of("bound",true,"identity",identity,"manifest_sha256","a".repeat(64),"source_pts_us",100));
        when(db.queryForList(contains("AS facts"),eq(frame))).thenAnswer(invocation->List.of(Map.of("facts",cn.trial.videosearch.support.CanonicalJson.encode(facts,4096).payload())));
        String signature=assets.databaseSignature(asset,frame);
        var binding=Map.<String,Object>of("frame_id",frame.toString(),"database_sha256",signature);
        assets.verifyDatabaseBinding(asset,binding);facts.put("source_pts_us",101);
        assertEquals("export_source_changed",assertThrows(InputRejected.class,()->assets.verifyDatabaseBinding(asset,binding)).code);
        assets.verifyDatabaseBinding(asset,Map.of("frame_id",frame.toString()));
        facts.put("bound",false);
        assertThrows(InputRejected.class,()->assets.verifyDatabaseBinding(asset,Map.of("frame_id",frame.toString())));
        verifyNoInteractions(images,recordings,clips,sessions);
    }
}
