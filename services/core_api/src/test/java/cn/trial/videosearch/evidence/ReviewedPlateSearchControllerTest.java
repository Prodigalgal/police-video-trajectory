package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.UserService;
import cn.trial.videosearch.support.ActorAccess;
import cn.trial.videosearch.support.BusinessEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.*;
import static org.mockito.Mockito.*;

class ReviewedPlateSearchControllerTest {
    @Test void requiresCurrentAccountAndAllowsReadersWithoutAdminPrivileges(){
        var users=mock(UserService.class);var status=mock(ReviewedPlateSearchStatus.class);UUID id=UUID.randomUUID();
        var client=WebTestClient.bindToController(new ReviewedPlateSearchController(new BusinessEndpoint(new ActorAccess(users,"protocol-control-token")),status)).build();
        String path="/internal/specialist-jobs/observations/"+id+"/plate-search?observationVersion=1";
        client.get().uri(path).exchange().expectStatus().isUnauthorized();
        when(users.status("reader",1)).thenReturn(Map.of("valid",false));
        client.get().uri(path).header("Authorization","Bearer protocol-control-token").header("X-Actor-Id","reader").header("X-Actor-Revision","1").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(status);
        when(users.status("reader",1)).thenReturn(Map.of("valid",true));when(status.read(id,1)).thenReturn(Map.of("state","pending"));
        client.get().uri(path).header("Authorization","Bearer protocol-control-token").header("X-Actor-Id","reader").header("X-Actor-Revision","1").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody().jsonPath("$.state").isEqualTo("pending");
        verify(users,never()).requireAdmin("reader");
    }
}
