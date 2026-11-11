package cn.trial.videosearch.cluster;

import cn.trial.videosearch.evidence.InputRejected;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClusterCoordinationTest {
    private JdbcTemplate budgetDb(){
        var db=mock(JdbcTemplate.class);
        when(db.queryForList(startsWith("SELECT total,text_reserved"))).thenReturn(List.of(Map.of("total",2,"text_reserved",1)));
        return db;
    }
    @Test void serviceTokensAreSeparatedAndNeverReachBusinessCodeWhenMissing(){
        var login=mock(SharedLoginState.class);var admission=mock(ModelAdmission.class);
        var controller=new ClusterControlController(login,admission,"web-test","gateway-test");
        assertEquals(401,controller.issue("Bearer gateway-test",null).block().getStatusCode().value());
        assertEquals(401,controller.acquire("Bearer web-test",null).block().getStatusCode().value());
        assertEquals(401,controller.consume(null,null).block().getStatusCode().value());
        verifyNoInteractions(login,admission);
    }
    @Test void optionalModeAndInvalidInputFailBeforeDatabaseWrites(){
        var db=mock(JdbcTemplate.class);var service=new SharedLoginState(db,false);
        assertFalse(service.ready());
        assertEquals("cluster_coordination_disabled",assertThrows(InputRejected.class,()->service.consume(new SharedLoginState.Consume(null,"a".repeat(64)))).code);
        assertThrows(IllegalArgumentException.class,()->service.consume(new SharedLoginState.Consume(null,"raw-address")));
        assertThrows(IllegalArgumentException.class,()->new ModelAdmission(db,true,2,2));
        verifyNoInteractions(db);
    }
    @Test void textReservationAndTotalBudgetApplyToEveryConsumer(){
        var db=budgetDb();var budget=new ModelAdmission(db,true,2,1);
        when(db.queryForList(startsWith("SELECT count(*) AS total"))).thenReturn(List.of(Map.of("total",1,"vision",1)));
        assertEquals(false,budget.acquire(new ModelAdmission.Acquire(UUID.randomUUID(),"gateway-b",true,375)).get("granted"));
        var text=budget.acquire(new ModelAdmission.Acquire(UUID.randomUUID(),"gateway-b",false,375));assertEquals(true,text.get("granted"));
        when(db.queryForList(startsWith("SELECT count(*) AS total"))).thenReturn(List.of(Map.of("total",2,"vision",1)));
        assertEquals(false,budget.acquire(new ModelAdmission.Acquire(UUID.randomUUID(),"gateway-c",false,375)).get("granted"));
        verify(db,times(1)).update(startsWith("INSERT INTO cluster_model_leases"),any(UUID.class),anyString(),anyBoolean(),anyInt());
    }
    @Test void mixedReplicaPoliciesAndForeignLeaseReplayAreRejected(){
        var db=budgetDb();var budget=new ModelAdmission(db,true,2,1);UUID identity=UUID.randomUUID();
        when(db.queryForList(startsWith("SELECT consumer,vision"),eq(identity))).thenReturn(List.of(Map.of("consumer","gateway-a","vision",true)));
        assertEquals(true,budget.acquire(new ModelAdmission.Acquire(identity,"gateway-a",true,375)).get("granted"));
        assertEquals("model_lease_conflict",assertThrows(InputRejected.class,()->budget.acquire(new ModelAdmission.Acquire(identity,"gateway-b",true,375))).code);
        when(db.queryForList(startsWith("SELECT total,text_reserved"))).thenReturn(List.of(Map.of("total",3,"text_reserved",1)));
        assertEquals("cluster_model_policy_conflict",assertThrows(InputRejected.class,budget::ready).code);
        assertThrows(IllegalArgumentException.class,()->budget.acquire(new ModelAdmission.Acquire(identity,"gateway-a",true,631)));
    }
    @Test void challengeConsumptionIsOneUseAndUnknownProofStillCountsAgainstTheSharedLimit(){
        var db=mock(JdbcTemplate.class);var login=new SharedLoginState(db,true);String id="x".repeat(43),key="b".repeat(64);
        when(db.queryForObject(startsWith("SELECT (extract"),eq(Long.class))).thenReturn(100_000L);
        when(db.queryForObject(startsWith("SELECT count(*) FROM cluster_login_limits"),eq(Long.class),eq("login"))).thenReturn(0L);
        when(db.queryForList(startsWith("DELETE FROM cluster_login_challenges"),eq(id))).thenReturn(List.of(Map.of("payload","{\"cookie\":\"bound\"}")),List.of());
        assertEquals(true,login.consume(new SharedLoginState.Consume(id,key)).get("available"));
        assertEquals(false,login.consume(new SharedLoginState.Consume(id,key)).get("available"));
        verify(db,times(2)).update(startsWith("INSERT INTO cluster_login_limits"),eq("login"),eq(key),anyString());
    }
    @Test void slidingWindowDoesNotResetAtAMinuteBoundaryAndStorageIsBounded(){
        var db=mock(JdbcTemplate.class);var login=new SharedLoginState(db,true);
        when(db.queryForObject(startsWith("SELECT (extract"),eq(Long.class))).thenReturn(120_010L);
        when(db.queryForList(startsWith("SELECT attempts"),eq("login"),anyString())).thenReturn(List.of(Map.of("attempts","[119990,119991,119992,119993,119994]")));
        assertEquals(429,assertThrows(InputRejected.class,()->login.consume(new SharedLoginState.Consume(null,"a".repeat(64)))).status);
        verify(db,never()).update(startsWith("INSERT INTO cluster_login_limits"),anyString(),anyString(),anyString());
    }
}
