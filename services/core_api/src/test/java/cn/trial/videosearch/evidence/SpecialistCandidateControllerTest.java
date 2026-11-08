package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SpecialistCandidateControllerTest {
    private final SpecialistCandidates candidates=mock(SpecialistCandidates.class);
    private final UserService users=mock(UserService.class);
    private WebTestClient client(){return WebTestClient.bindToController(new SpecialistCandidateController(candidates,users,"candidate-admin-test")).build();}
    @Test void tokenAndCurrentCredentialRevisionAreRequired(){
        var client=client();client.get().uri("/internal/specialist-jobs/candidates").exchange().expectStatus().isUnauthorized();
        when(users.status("admin",1)).thenReturn(Map.of("valid",false));
        client.get().uri("/internal/specialist-jobs/candidates").header("Authorization","Bearer candidate-admin-test").header("X-Actor-Id","admin").header("X-Actor-Revision","1").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(candidates);
    }
    @Test void ordinaryUserCannotReadTheManagementLedger(){
        when(users.status("reader",1)).thenReturn(Map.of("valid",true));doThrow(new AdminService.Rejected(403,"admin_required")).when(users).requireAdmin("reader");
        client().get().uri("/internal/specialist-jobs/candidates").header("Authorization","Bearer candidate-admin-test").header("X-Actor-Id","reader").header("X-Actor-Revision","1").exchange().expectStatus().isForbidden();
        verifyNoInteractions(candidates);
    }
    @Test void administratorReadsABoundedScopedPage(){
        when(users.status("admin",1)).thenReturn(Map.of("valid",true));when(candidates.list("recording-01","waiting_budget",2,null)).thenReturn(Map.of("enabled",false));
        client().get().uri("/internal/specialist-jobs/candidates?camera_id=recording-01&state=waiting_budget&limit=2").header("Authorization","Bearer candidate-admin-test").header("X-Actor-Id","admin").header("X-Actor-Revision","1").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody().jsonPath("$.enabled").isEqualTo(false);
    }
}
