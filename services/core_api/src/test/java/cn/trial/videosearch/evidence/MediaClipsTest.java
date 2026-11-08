package cn.trial.videosearch.evidence;

import cn.trial.videosearch.ingest.WorkerController;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MediaClipsTest {
    @TempDir Path directory;
    @Test void anUnknownManifestIsRejectedBeforeAnyRecordingRead()throws Exception{
        UUID id=UUID.randomUUID();Path folder=directory.resolve(id.toString().replace("-",""));Files.createDirectory(folder);Files.writeString(folder.resolve("manifest.json"),"{\"source_ref\":\"never expose\"}");
        var files=new MediaClipFiles(directory.toString(),directory.resolve("missing-originals").toString());
        assertEquals("media_manifest_changed",assertThrows(InputRejected.class,()->files.verify(id,"a".repeat(64),MediaClipFiles.Mode.MANIFEST,-1)).code);
    }
    @Test void hiddenCameraDoesNotReadAnyAssets(){
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(MediaClipFiles.class);var manager=mock(PlatformTransactionManager.class);
        var clips=new MediaClips(db,search,files,manager,mock(SessionMediaInputs.class));
        assertEquals("media_unavailable",assertThrows(InputRejected.class,()->clips.list("recording-05",20,null)).code);verifyNoInteractions(db,files,manager);
    }
    @Test void sourceMutationAfterFileIoRollsBackTheShortRegistrationTransaction(){
        var db=mock(JdbcTemplate.class);var search=mock(SearchOrchestrator.class);var files=mock(MediaClipFiles.class);var manager=mock(PlatformTransactionManager.class);
        var clips=new MediaClips(db,search,files,manager,mock(SessionMediaInputs.class));UUID clip=UUID.randomUUID(),source=UUID.randomUUID();
        var assets=new MediaClipFiles.Assets(Map.of(),"{}","a".repeat(64),source,"recording-01","b".repeat(64),10,directory.resolve("clip.mp4"),1,null);
        when(files.verify(clip,"a".repeat(64),MediaClipFiles.Mode.MANIFEST,-1)).thenAnswer(call->{verifyNoInteractions(manager);return assets;});
        doAnswer(call->{verifyNoInteractions(manager);return null;}).when(files).verifyRecording(assets);
        when(search.mayReadCamera("recording-01")).thenReturn(true);
        when(db.queryForList(startsWith("SELECT source_id,camera_id"),eq(source))).thenReturn(List.of(Map.of("source_id",source,"camera_id","recording-01","source_type","archive","source_ref","archive:recording-01","source_time_origin","unknown")),List.of(Map.of("source_id",source,"camera_id","recording-01","source_type","archive","source_ref","archive:recording-01","source_time_origin","verified")));
        var status=new SimpleTransactionStatus();when(manager.getTransaction(any())).thenReturn(status);
        assertEquals("media_source_changed",assertThrows(InputRejected.class,()->clips.register(new MediaClips.Register(clip,"a".repeat(64),"media-worker"))).code);
        verify(manager).rollback(status);verify(db,never()).update(startsWith("INSERT INTO recorded_media_clips"),any(Object[].class));
    }
    @Test void videoRangesUseTheSameBoundedContractAsHistoricalPlayback()throws Exception{
        UUID clip=UUID.randomUUID();Path video=directory.resolve("clip.mp4");Files.write(video,new byte[]{0,1,2,3,4,5,6,7,8,9});
        var clips=mock(MediaClips.class);var search=mock(SearchOrchestrator.class);when(search.authorized("Bearer search")).thenReturn(true);
        when(clips.verify(clip,MediaClipFiles.Mode.VIDEO,-1)).thenReturn(new MediaClipFiles.Assets(Map.of(),"{}","a".repeat(64),UUID.randomUUID(),"recording-01","b".repeat(64),10,video,10,null));
        var web=WebTestClient.bindToController(new MediaClipController(clips,search)).build();String url="/api/v1/media-clips/"+clip+"/video";
        web.get().uri(url).exchange().expectStatus().isUnauthorized();
        web.get().uri(url).header("Authorization","Bearer search").header("Range","bytes=4-7").exchange().expectStatus().isEqualTo(206).expectHeader().contentType(MediaType.valueOf("video/mp4")).expectHeader().valueEquals("Content-Range","bytes 4-7/10").expectBody(byte[].class).isEqualTo(new byte[]{4,5,6,7});
        web.get().uri(url).header("Authorization","Bearer search").header("Range","bytes=20-30").exchange().expectStatus().isEqualTo(416).expectHeader().valueEquals("Content-Range","bytes */10");
    }
    @Test void mediaRegistrationRequiresTheWorkerToken(){
        var clips=mock(MediaClips.class);UUID id=UUID.randomUUID();when(clips.register(any())).thenReturn(Map.of("clip_id",id.toString(),"manifest_sha256","a".repeat(64),"inference_invoked",false));
        var web=WebTestClient.bindToController(new WorkerController(null,null,null,null,null,null,clips,null,"worker-token")).build();
        var body=Map.of("clipId",id,"manifestSha256","a".repeat(64),"workerId","media-worker");
        web.post().uri("/internal/v1/media-clips/register").header("Authorization","Bearer search").bodyValue(body).exchange().expectStatus().isUnauthorized();
        web.post().uri("/internal/v1/media-clips/register").header("Authorization","Bearer worker-token").bodyValue(body).exchange().expectStatus().isOk().expectBody().jsonPath("$.clip_id").isEqualTo(id.toString());
    }
}
