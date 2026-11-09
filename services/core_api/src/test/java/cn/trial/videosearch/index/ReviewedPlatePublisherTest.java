package cn.trial.videosearch.index;

import cn.trial.videosearch.evidence.ReviewedPlateFacts;
import cn.trial.videosearch.http.SecureJsonClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewedPlatePublisherTest {
    @Test void publishesOutsideTransactionAndFencesCompletionByLeaseGeneration(){
        var db=mock(JdbcTemplate.class);var facts=mock(ReviewedPlateFacts.class);var manager=mock(PlatformTransactionManager.class);
        var active=new AtomicBoolean();UUID observation=UUID.randomUUID(),frame=UUID.randomUUID();
        when(manager.getTransaction(any())).thenAnswer(call->{assertFalse(active.getAndSet(true));return new SimpleTransactionStatus();});
        doAnswer(call->{active.set(false);return null;}).when(manager).commit(any());
        when(facts.enabled()).thenReturn(true);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("observation_id",observation,"requested_revision",3L,"lease_generation",7L)));
        when(facts.resolve(observation)).thenAnswer(call->{assertFalse(active.get());return new ReviewedPlateFacts.Snapshot(observation,1,frame,"camera-1","source","ready","闽E12345",List.of(),"fact");});
        try(var clients=mockConstruction(SecureJsonClient.class,(client,context)->{
            when(client.get(anyString(),anyString(),any())).thenAnswer(call->{assertFalse(active.get());return Map.of("_source",Map.of("observation_id",observation.toString(),"record_version",1,"frame_id",frame.toString(),"camera_id","camera-1","target_type","vehicle","deleted",false));});
            doAnswer(call->{assertFalse(active.get());return null;}).when(client).putVersioned(anyString(),any(),anyLong(),anyMap(),anyString());
        })){
            var publisher=new ReviewedPlatePublisher(db,manager,facts,"https://disabled.invalid","unused","user","pass","police-video-observations","reader","read-pass");
            assertTrue(publisher.processOne());
            verify(clients.constructed().getFirst()).putVersioned(eq("police-video-observations-plates"),eq(observation),eq(3L),argThat(document->"闽E12345".equals(document.get("reviewed_plate_number"))&&Long.valueOf(3L).equals(document.get("plate_projection_revision"))),anyString());
            verify(db).update(contains("lease_generation=?"),eq(3L),eq(1L),eq("source"),eq("fact"),eq(3L),eq("ready"),eq(observation),anyString(),eq(8L));
            assertFalse(active.get());
        }
    }
    @Test void disabledWorkerDoesNotClaimAndRetryRecoversExpiredFinalLease(){
        var db=mock(JdbcTemplate.class);var facts=mock(ReviewedPlateFacts.class);
        try(var clients=mockConstruction(SecureJsonClient.class)){
            var publisher=new ReviewedPlatePublisher(db,mock(PlatformTransactionManager.class),facts,"https://disabled.invalid","unused","user","pass","police-video-observations","reader","read-pass");
            assertFalse(publisher.processOne());verifyNoInteractions(db);when(facts.enabled()).thenReturn(true);
            publisher.retryFailed();verify(db).update(contains("lease_expires_at<=clock_timestamp()"),eq(10));
        }
    }
}
