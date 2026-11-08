package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaJobsTest {
    @Test void disabledQueueNeverReadsNewTablesOrArchives(){
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(MediaClipFiles.class);var clips=mock(MediaClips.class);var manager=mock(PlatformTransactionManager.class);
        var jobs=new MediaJobs(db,search,files,clips,mock(SessionMediaInputs.class),manager,false,200,1,3);
        assertEquals("media_jobs_disabled",assertThrows(InputRejected.class,()->jobs.create(null)).code);
        assertEquals("media_jobs_disabled",assertThrows(InputRejected.class,()->jobs.claim("worker",120)).code);
        verifyNoInteractions(db,search,files,clips,manager);
    }
    @Test void requestAndWorkerTokensHaveDifferentCapabilities(){
        var jobs=mock(MediaJobs.class);var search=mock(SearchOrchestrator.class);when(search.authorized("Bearer search")).thenReturn(true);
        var web=WebTestClient.bindToController(new MediaJobController(jobs,search,"worker-token")).build();var create=new MediaJobs.Create(UUID.randomUUID(),UUID.randomUUID(),0,2000);
        web.post().uri("/api/v1/media-jobs").header("Authorization","Bearer worker-token").bodyValue(create).exchange().expectStatus().isUnauthorized();
        web.post().uri("/internal/v1/media-jobs/claim").header("Authorization","Bearer search").bodyValue(Map.of("workerId","worker","leaseSeconds",120)).exchange().expectStatus().isUnauthorized();verifyNoInteractions(jobs);
        when(jobs.create(create)).thenReturn(Map.of("state","queued"));
        web.post().uri("/api/v1/media-jobs").header("Authorization","Bearer search").bodyValue(create).exchange().expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("queued");
        UUID id=UUID.randomUUID();when(jobs.claim("worker",120)).thenReturn(Optional.of(new MediaJobs.Job(id,UUID.randomUUID(),"recording-01",0,2000,MediaJobs.PROFILE,"a".repeat(64),"b".repeat(64),100,"c".repeat(64),0,1,Instant.now().plusSeconds(120),"archive",null)));
        web.post().uri("/internal/v1/media-jobs/claim").header("Authorization","Bearer worker-token").bodyValue(Map.of("workerId","worker","leaseSeconds",120)).exchange().expectStatus().isOk().expectBody().jsonPath("$.job.jobId").isEqualTo(id.toString()).jsonPath("$.job.generation").isEqualTo(1).jsonPath("$.job.expiresAt").exists();
    }
    @Test void sourceChangeAfterFileIoRollsBackBeforeAnyJobIsCreated(){
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(MediaClipFiles.class);var clips=mock(MediaClips.class);var manager=mock(PlatformTransactionManager.class);
        UUID source=UUID.randomUUID();when(db.queryForList("SELECT camera_id FROM sources WHERE source_id=?",source)).thenReturn(List.of(Map.of("camera_id","recording-01")));
        when(clips.source(source,"recording-01")).thenReturn("a".repeat(64),"b".repeat(64));
        when(files.inspectRecording("recording-01")).thenAnswer(call->{verifyNoInteractions(manager);return Map.of("bytes",10,"sha256","c".repeat(64));});
        var status=new SimpleTransactionStatus();when(manager.getTransaction(any())).thenReturn(status);
        var jobs=new MediaJobs(db,search,files,clips,mock(SessionMediaInputs.class),manager,true,200,1,3);
        assertEquals("media_source_changed",assertThrows(InputRejected.class,()->jobs.create(new MediaJobs.Create(UUID.randomUUID(),source,0,2000))).code);
        verify(manager).rollback(status);verify(db,never()).update(startsWith("INSERT INTO media_production_jobs"),any(Object[].class));
    }
    @Test void invalidOrExpiredCompletionDoesNotReadAnyMedia(){
        var db=mock(JdbcTemplate.class);var files=mock(MediaClipFiles.class);var clips=mock(MediaClips.class);
        var jobs=new MediaJobs(db,mock(SearchOrchestrator.class),files,clips,mock(SessionMediaInputs.class),mock(PlatformTransactionManager.class),true,200,1,3);
        assertEquals("media_lease_expired",assertThrows(InputRejected.class,()->jobs.complete(new MediaJobs.Complete(UUID.randomUUID(),"old-worker",1,"a".repeat(64),"b".repeat(64)))).code);
        verifyNoInteractions(files,clips);
    }
    @Test void hiddenCameraAndInvalidWindowNeverInspectOriginals(){
        var db=mock(JdbcTemplate.class);var files=mock(MediaClipFiles.class);var clips=mock(MediaClips.class);var manager=mock(PlatformTransactionManager.class);UUID source=UUID.randomUUID();
        var jobs=new MediaJobs(db,mock(SearchOrchestrator.class),files,clips,mock(SessionMediaInputs.class),manager,true,200,1,3);
        assertThrows(IllegalArgumentException.class,()->jobs.create(new MediaJobs.Create(UUID.randomUUID(),source,-1,3000)));
        when(db.queryForList("SELECT camera_id FROM sources WHERE source_id=?",source)).thenReturn(List.of(Map.of("camera_id","recording-05")));
        when(clips.source(source,"recording-05")).thenThrow(new InputRejected(404,"media_source_unavailable"));
        assertThrows(InputRejected.class,()->jobs.create(new MediaJobs.Create(UUID.randomUUID(),source,0,2000)));verifyNoInteractions(files,manager);
    }
}
