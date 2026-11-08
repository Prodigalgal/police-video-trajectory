package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.*;
import static org.mockito.Mockito.*;

class SpecialistControllerTest {
    @Test void observationDiscoveryChecksAccountAndKeepsRevisionCursorAndLimit(){
        var jobs=mock(SpecialistJobs.class);var users=mock(UserService.class);var query=mock(ObservationSpecialists.class);
        var client=WebTestClient.bindToController(new SpecialistController(jobs,users,query,"admin-secret","worker-secret")).build();
        UUID observation=UUID.randomUUID(),after=UUID.randomUUID();String url="/internal/specialist-jobs/observations/"+observation+"?observationVersion=3&limit=2&after="+after;
        client.get().uri(url).header("Authorization","Bearer worker-secret").exchange().expectStatus().isUnauthorized();verifyNoInteractions(query);
        when(users.status("reader",3L)).thenReturn(Map.of("valid",false));
        client.get().uri(url).header("Authorization","Bearer admin-secret").header("X-Actor-Id","reader").header("X-Actor-Revision","3").exchange().expectStatus().isUnauthorized();verifyNoInteractions(query);
        when(users.status("reader",3L)).thenReturn(Map.of("valid",true));when(query.list(observation,3L,2,after)).thenReturn(Map.of("items",List.of(),"has_more",false));
        client.get().uri(url).header("Authorization","Bearer admin-secret").header("X-Actor-Id","reader").header("X-Actor-Revision","3").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store");
        verify(query).list(observation,3L,2,after);verifyNoInteractions(jobs);
        when(query.list(observation,3L,2,after)).thenThrow(new InputRejected(409,"observation_revision_conflict"));
        client.get().uri(url).header("Authorization","Bearer admin-secret").header("X-Actor-Id","reader").header("X-Actor-Revision","3").exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("observation_revision_conflict");
    }
    @Test void workerAndUserScopesRemainSeparateAndUserRevisionIsRechecked(){
        var jobs=mock(SpecialistJobs.class);var users=mock(UserService.class);var client=WebTestClient.bindToController(new SpecialistController(jobs,users,mock(ObservationSpecialists.class),"admin-secret","worker-secret")).build();UUID input=UUID.randomUUID();var body=new SpecialistJobs.Create(UUID.randomUUID(),"a".repeat(64),"license_plate");
        client.post().uri("/internal/specialist-jobs/inputs/"+input).header("Authorization","Bearer worker-secret").bodyValue(body).exchange().expectStatus().isUnauthorized();
        client.post().uri("/internal/v1/specialist-jobs/claim").header("Authorization","Bearer admin-secret").bodyValue(Map.of("workerId","worker","leaseSeconds",120,"profile","profile","operations",List.of("license_plate"))).exchange().expectStatus().isUnauthorized();verifyNoInteractions(jobs);
        when(users.status("reader",2L)).thenReturn(Map.of("valid",false));client.get().uri("/internal/specialist-jobs/capabilities").header("Authorization","Bearer admin-secret").header("X-Actor-Id","reader").header("X-Actor-Revision","2").exchange().expectStatus().isUnauthorized();verifyNoInteractions(jobs);
        when(users.status("reader",3L)).thenReturn(Map.of("valid",true));when(jobs.create(input,body,"reader")).thenReturn(Map.of("state","queued"));client.post().uri("/internal/specialist-jobs/inputs/"+input).header("Authorization","Bearer admin-secret").header("X-Actor-Id","reader").header("X-Actor-Revision","3").bodyValue(body).exchange().expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("queued");verify(jobs).create(input,body,"reader");
    }
}
