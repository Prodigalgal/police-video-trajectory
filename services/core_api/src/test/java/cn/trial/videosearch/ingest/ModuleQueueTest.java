package cn.trial.videosearch.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.trial.videosearch.admin.AdminController;
import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModuleQueueTest {
    @Test void canonicalDigestIgnoresMapOrderButRejectsUnboundedOrNonFiniteValues(){
        var source=new ModuleResultSource();var first=new LinkedHashMap<String,Object>();
        first.put("b",List.of(1,2));first.put("a","value");
        assertEquals(source.encodeValue(first).sha256(),source.encodeValue(Map.of("a","value","b",List.of(1,2))).sha256());
        assertThrows(ModuleResultSource.Invalid.class,()->source.encodeValue(Map.of("huge","x".repeat(600000))));
        assertThrows(ModuleResultSource.Invalid.class,()->source.encodeValue(Map.of("value",Double.NaN)));
    }
    @Test void sourceDigestAndFrameBindingAreMandatory(){
        var codec=new ModuleResultSource();UUID frame=UUID.randomUUID();
        var source=Map.of("schema_version","analysis-result-source-v1","frame_id",frame.toString(),"model_id","protocol-fixture",
                "prompt_version","protocol-fixture","targets",List.of(),"additional_objects",List.of());
        var encoded=codec.encodeValue(source);
        assertEquals(source,codec.decode(encoded.payload(),encoded.sha256(),frame));
        assertThrows(ModuleResultSource.Invalid.class,()->codec.decode(encoded.payload(),"0".repeat(64),frame));
        assertThrows(ModuleResultSource.Invalid.class,()->codec.decode(encoded.payload(),encoded.sha256(),UUID.randomUUID()));
        assertThrows(ModuleResultSource.Invalid.class,()->codec.decode("[]",encoded.sha256(),frame));
    }
    @Test void invalidQuotaCannotStartAndExistingRetryPolicyIsReused(){
        assertThrows(IllegalArgumentException.class,()->new ModuleQueuePolicy(2,2,1,1,1,5,5,300));
        assertThrows(IllegalArgumentException.class,()->new ModuleQueuePolicy(30,2,3,1,1,5,5,300));
        assertThrows(IllegalArgumentException.class,()->new ModuleQueuePolicy(30,2,1,1,1,0,5,300));
        var policy=new ModuleQueuePolicy(30,2,1,1,1,5,5,300);
        assertEquals(300,policy.retryDelaySeconds(30));
        assertThrows(IllegalArgumentException.class,()->policy.kindLimit("unknown"));
    }
    @Test void workerAuthenticationPrecedesModuleAccess(){
        var modules=mock(ModuleQueue.class);var controller=new WorkerController(null,null,null,null,modules,null,null,null,"worker-token");
        assertEquals(401,controller.claimModule("Bearer wrong",new WorkerController.ModuleClaim("worker",120,null)).block().getStatusCode().value());
        assertEquals(401,controller.completeModule(null,new ModuleQueue.Completion(UUID.randomUUID(),"worker",1,UUID.randomUUID(),"a".repeat(64))).block().getStatusCode().value());
        verifyNoInteractions(modules);
    }
    @Test void missingModuleOrWrongTargetMembershipCannotProducePartialWork(){
        for(boolean missing:new boolean[]{true,false}){
            var db=mock(JdbcTemplate.class);when(db.queryForObject(anyString(),eq(Long.class),any(UUID.class))).thenReturn(0L);
            var scene=Map.<String,Object>of("module_key","scene_context","target_ids","[]");
            var person=Map.<String,Object>of("module_key","person_attributes","target_ids","[]");
            when(db.queryForList(anyString(),any(UUID.class))).thenReturn(missing?List.of(scene):List.of(person,scene));
            var queue=new ModuleQueue(db,new ModuleQueuePolicy(30,2,1,1,1,5,5,300));
            var completion=new TargetPipeline.Completion(UUID.randomUUID(),"protocol-worker",1,"protocol-fixture","protocol-fixture",
                    List.of(new TargetPipeline.TargetResult(UUID.randomUUID(),"person",null,Map.of(),null,List.of())),List.of(),null);
            assertThrows(IllegalStateException.class,()->queue.enqueue(UUID.randomUUID(),completion));
            verify(db,never()).update(anyString(),any(Object[].class));
        }
    }
    @Test void moduleManagementRequiresAdministratorBeforeServiceCall(){
        var service=mock(AdminService.class);var users=mock(UserService.class);var controller=new AdminController(service,users,"admin-token");
        assertEquals(401,controller.moduleJobs("Bearer wrong","admin","all",null,null,20,null).block().getStatusCode().value());
        doThrow(new AdminService.Rejected(403,"admin_required")).when(users).requireAdmin("reader");
        assertEquals(403,controller.retryModule("Bearer admin-token","reader",UUID.randomUUID(),new AdminService.Retry(UUID.randomUUID(),1,"protocol fixture")).block().getStatusCode().value());
        verifyNoInteractions(service);
    }
}
