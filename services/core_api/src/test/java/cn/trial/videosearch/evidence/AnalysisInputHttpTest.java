package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.UserService;
import cn.trial.videosearch.ingest.WorkerController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalysisInputHttpTest {
    final AnalysisInputs inputs=mock(AnalysisInputs.class);
    final UserService users=mock(UserService.class);
    final WebTestClient web=WebTestClient.bindToController(new AnalysisInputController(inputs,users,"service-token")).build();
    @Test void gatewayContractDeserializesWindowAndActorWithoutAdminRole(){
        when(users.status("reader",1L)).thenReturn(Map.of("valid",true,"is_admin",false));
        when(inputs.create(any(),eq("reader"))).thenReturn(Map.of("creation_invoked_inference",false));
        UUID request=UUID.randomUUID(),observation=UUID.randomUUID();
        web.post().uri("/internal/analysis-inputs").header("Authorization","Bearer service-token").header("X-Actor-Id","reader").header("X-Actor-Revision","1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("requestId",request,"observationId",observation,"observationVersion",2,"evidenceSha256","a".repeat(64),"purpose","frame_sequence",
                "window",Map.of("beforeMs",15000,"afterMs",15000,"maxFrames",16,"maxGapMs",6000)))
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody().jsonPath("$.creation_invoked_inference").isEqualTo(false);
        verify(inputs).create(argThat(body->body.requestId().equals(request)&&body.observationId().equals(observation)&&body.observationVersion()==2L&&body.window().equals(new TemporalWindow(15000,15000,16,6000))),eq("reader"));
    }
    @Test void staleManifestPreservesConflictAndNeverReturnsAFile(){
        UUID input=UUID.randomUUID();when(users.status("reader",1L)).thenReturn(Map.of("valid",true));
        when(inputs.verify(input,null)).thenThrow(new InputRejected(409,"input_source_changed"));
        web.get().uri("/internal/analysis-inputs/"+input+"/manifest").header("Authorization","Bearer service-token").header("X-Actor-Id","reader").header("X-Actor-Revision","1")
            .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("input_source_changed");
    }
    @Test void workerImageContractReturnsOnlyTheRequestedVerifiedBytes(){
        UUID input=UUID.randomUUID(),frame=UUID.randomUUID();
        when(inputs.verify(input,frame)).thenReturn(new AnalysisInputs.Verified(Map.of(),"{}","b".repeat(64),new EvidenceFiles.FrameFile(new byte[]{1,2,3},1280,720,"a".repeat(64))));
        var worker=WebTestClient.bindToController(new WorkerController(null,null,null,null,null,inputs,null,null,"worker-token")).build();
        worker.get().uri("/internal/v1/analysis-inputs/"+input+"/frames/"+frame+"/image").header("Authorization","Bearer worker-token").exchange()
            .expectStatus().isOk().expectHeader().contentType(MediaType.IMAGE_JPEG).expectHeader().valueEquals("X-Content-SHA256","a".repeat(64)).expectBody(byte[].class).isEqualTo(new byte[]{1,2,3});
        worker.get().uri("/internal/v1/analysis-inputs/"+input+"/manifest").header("Authorization","Bearer service-token").exchange().expectStatus().isUnauthorized();
    }
}
