package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.support.ActorAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** HTTP transport fixtures only; these bytes are never submitted as visual model inputs. */
class ExportHttpStreamingTest {
    @TempDir Path directory;
    @Test void workerAssetUsesTheDataBufferWriterAndRetainsJsonErrors()throws Exception{
        var jobs=mock(ExportJobs.class);var assets=mock(ExportAssets.class);var lifecycle=mock(ExportArtifactLifecycle.class);
        UUID id=UUID.randomUUID();var asset=Map.<String,Object>of("asset_key","image-key");
        when(jobs.workerSnapshot(id,"worker",1)).thenReturn(Map.of("payload","{\"assets\":[{\"asset_key\":\"image-key\"}]}"));
        byte[] bytes={1,2,3,4,5};when(assets.read(asset)).thenReturn(new ExportAssets.Content(null,bytes,bytes.length,"a".repeat(64),"image/jpeg"));
        var web=WebTestClient.bindToController(new ExportWorkerController(jobs,assets,lifecycle,mock(ExportRuntime.class),"token")).build();
        String path="/internal/v2/exports/"+id+"/assets/image-key?worker=worker&generation=1";
        web.get().uri(path).header("Authorization","Bearer token").exchange().expectStatus().isOk().expectHeader().contentType(MediaType.IMAGE_JPEG).expectBody(byte[].class).isEqualTo(bytes);
        web.get().uri(path).exchange().expectStatus().isUnauthorized().expectHeader().contentType(MediaType.APPLICATION_JSON).expectBody().jsonPath("$.code").isEqualTo("unauthorized");
        when(jobs.workerSnapshot(id,"worker",1)).thenThrow(new InputRejected(409,"export_lease_expired"));
        web.get().uri(path).header("Authorization","Bearer token").exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("export_lease_expired");
    }
    @Test void downloadStreamsTheExactRangeAndFinalizesItsTransfer()throws Exception{
        var jobs=mock(ExportJobs.class);var access=mock(ActorAccess.class);UUID id=UUID.randomUUID();Path path=directory.resolve("package.zip");Files.write(path,new byte[]{0,1,2,3,4,5,6,7});
        var transfer=new ExportJobs.Transfer(UUID.randomUUID(),id,new ExportFiles.Artifact(path,8,"a".repeat(64)),2,5);
        when(jobs.download(id,"admin","bytes=2-5")).thenReturn(transfer);
        var web=WebTestClient.bindToController(new ExportDownloadController(jobs,access)).build();
        web.get().uri("/internal/exports/"+id+"/download").header("X-Actor-Id","admin").header("Range","bytes=2-5").exchange()
            .expectStatus().isEqualTo(206).expectHeader().contentType(MediaType.parseMediaType("application/zip")).expectHeader().valueEquals("Content-Range","bytes 2-5/8")
            .expectBody(byte[].class).isEqualTo(new byte[]{2,3,4,5});
        verify(jobs).finishTransfer(transfer,4,"server_complete");
    }
    @Test void downloadAuthorizationFailuresRemainBoundedJson(){
        var jobs=mock(ExportJobs.class);var access=mock(ActorAccess.class);
        doThrow(new AdminService.Rejected(401,"unauthorized")).when(access).require(isNull(),isNull(),isNull(),eq(false));
        var web=WebTestClient.bindToController(new ExportDownloadController(jobs,access)).build();
        web.get().uri("/internal/exports/"+UUID.randomUUID()+"/download").exchange().expectStatus().isUnauthorized().expectHeader().contentType(MediaType.APPLICATION_JSON).expectBody().jsonPath("$.code").isEqualTo("unauthorized");
        verifyNoInteractions(jobs);
    }
}
