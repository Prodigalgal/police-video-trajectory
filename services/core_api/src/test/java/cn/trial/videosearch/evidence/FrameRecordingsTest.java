package cn.trial.videosearch.evidence;

import cn.trial.videosearch.ingest.WorkerController;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FrameRecordingsTest {
    @TempDir Path directory;
    @Test void disabledCapabilityNeverTouchesNewTablesOrFiles(){
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(StreamRecordingFiles.class);var images=mock(EvidenceFiles.class);var manager=mock(PlatformTransactionManager.class);
        var recordings=new FrameRecordings(db,search,files,images,manager,false);
        assertTrue(recordings.playback(UUID.randomUUID(),true).isEmpty());
        assertEquals("recording_disabled",assertThrows(InputRejected.class,()->recordings.register(null)).code);
        verifyNoInteractions(db,search,files,images,manager);
    }
    @Test void workerRegistrationAndManifestRequireTheDedicatedToken(){
        var service=mock(FrameRecordings.class);var web=WebTestClient.bindToController(new WorkerController(null,null,null,null,null,null,null,service,"worker-token")).build();
        UUID frame=UUID.randomUUID(),segment=UUID.randomUUID();
        var body=new FrameRecordings.Register(frame,"a".repeat(64),"b".repeat(32),segment,"c".repeat(64),123L,"d".repeat(64));
        web.post().uri("/internal/v1/frame-recordings/register").bodyValue(body).exchange().expectStatus().isUnauthorized();
        web.get().uri("/internal/v1/frame-recordings/"+frame+"/manifest").header("Authorization","Bearer search-token").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(service);
        when(service.register(body)).thenReturn(Map.of("recorded",true,"frame_id",frame.toString()));
        web.post().uri("/internal/v1/frame-recordings/register").header("Authorization","Bearer worker-token").bodyValue(body).exchange().expectStatus().isOk().expectBody().jsonPath("$.recorded").isEqualTo(true);
        when(service.playback(frame,false)).thenReturn(Optional.empty());
        web.get().uri("/internal/v1/frame-recordings/"+frame+"/manifest").header("Authorization","Bearer worker-token").exchange().expectStatus().isNotFound();
    }
    @Test void realtimePlaybackUsesOnlyTheVerifiedSegmentAndRetainsRanges()throws Exception{
        UUID frame=UUID.randomUUID();Path path=directory.resolve("protocol-range.mp4");Files.write(path,new byte[]{0,1,2,3,4,5,6,7,8,9});
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var service=mock(FrameRecordings.class);
        when(search.authorized("Bearer search")).thenReturn(true);when(search.mayReadCamera("recording-01")).thenReturn(true);
        when(db.queryForList(anyString(),eq(frame))).thenReturn(List.of(Map.of("camera_id","recording-01","source_type","rtsp","source_ref","simulator:recording-01")));
        when(service.playback(frame,true)).thenReturn(Optional.of(new FrameRecordings.Playback(UUID.randomUUID(),path,10,500,"a".repeat(64),Map.of())));
        var web=WebTestClient.bindToController(new ArchiveController(db,search,service,directory.resolve("originals").toString())).build();String url="/api/v1/frames/"+frame+"/archive";
        web.get().uri(url).exchange().expectStatus().isUnauthorized();verifyNoInteractions(db,service);
        web.get().uri(url).header("Authorization","Bearer search").header("Range","bytes=4-7").exchange().expectStatus().isEqualTo(206).expectHeader().valueEquals("Content-Range","bytes 4-7/10").expectBody(byte[].class).isEqualTo(new byte[]{4,5,6,7});
        web.get().uri(url).header("Authorization","Bearer search").header("Range","bytes=20-30").exchange().expectStatus().isEqualTo(416);
        when(service.playback(frame,true)).thenThrow(new InputRejected(409,"recording_pixels_changed"));
        web.get().uri(url).header("Authorization","Bearer search").exchange().expectStatus().isEqualTo(409);
    }
    @Test void recordingScopeAndBindingAreRecheckedAfterFileReads()throws Exception{
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(StreamRecordingFiles.class);var images=mock(EvidenceFiles.class);var manager=mock(PlatformTransactionManager.class);
        UUID frame=UUID.randomUUID(),segment=UUID.randomUUID(),task=UUID.randomUUID(),run=UUID.randomUUID();String sha="a".repeat(64),manifestSha="c".repeat(64),packetSha="d".repeat(64);
        var manifest=new HashMap<String,Object>();manifest.putAll(Map.of("task_id",task.toString(),"scene_run_id",run.toString(),"camera_id","recording-01","worker_id","worker-1","task_generation",1,"media_epoch",0,"source_ref_sha256",CanonicalJson.sha256("simulator:recording-01"),"origin_pts_us",1000000));
        var recording=new StreamRecordingFiles.Recording(manifest,"{}",manifestSha,directory.resolve("source.mp4"),1,5000000,1000000,Map.of(2000000L,packetSha));
        var capture=new HashMap<String,Object>();capture.putAll(Map.of("task_id",task,"scene_run_id",run,"camera_id","recording-01","worker_id","worker-1","lease_generation",1,"media_epoch",0,"source_ref","simulator:recording-01","payload_sha256",sha,"relative_media_ms",1000,"evidence_ref","evidence:"+frame));
        var changed=new HashMap<>(capture);changed.put("relative_media_ms",2000);
        when(files.read(anyString(),eq(segment),eq(manifestSha))).thenAnswer(call->{verifyNoInteractions(manager);return recording;});
        when(images.read(eq(frame),anyString())).thenAnswer(call->{verifyNoInteractions(manager);return new EvidenceFiles.FrameFile(new byte[]{1},1280,720,sha);});
        when(search.mayReadCamera("recording-01")).thenReturn(true);
        when(db.queryForList(contains("s.source_ref,f.analysis_state"),eq(frame))).thenReturn(List.of(capture),List.of(changed));
        var status=new SimpleTransactionStatus();when(manager.getTransaction(any())).thenReturn(status);
        var service=new FrameRecordings(db,search,files,images,manager,true);var request=new FrameRecordings.Register(frame,sha,"b".repeat(32),segment,manifestSha,2000000L,packetSha);
        assertEquals("recording_clock_conflict",assertThrows(InputRejected.class,()->service.register(request)).code);
        verify(manager).rollback(status);verify(db,never()).update(startsWith("INSERT INTO frame_recording_links"),any(Object[].class));
    }
    @Test void invalidNamespaceOrUnknownManifestNeverEscapesItsOwnedDirectory()throws Exception{
        var files=new StreamRecordingFiles(directory.toString());UUID id=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->files.read("../originals",id,"a".repeat(64)));
        String namespace="b".repeat(32);Path folder=directory.resolve(namespace).resolve(id.toString().replace("-",""));Files.createDirectories(folder);
        Files.writeString(folder.resolve("owner.json"),"{}");
        assertEquals("recording_manifest_changed",assertThrows(InputRejected.class,()->files.read(namespace,id,"a".repeat(64))).code);
    }
}
