package cn.trial.videosearch.ingress;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoPlatformServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final SearchOrchestrator scope = mock(SearchOrchestrator.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    private VideoPlatformService service() {
        when(scope.readableCameras()).thenReturn(Set.of("one", "two"));
        when(scope.mayReadCamera(anyString())).thenAnswer(call -> Set.of("one", "two").contains(call.getArgument(0)));
        when(tx.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return new VideoPlatformService(db, scope, List.of(new Gb28181CascadePlatformAdapter(), new PlatformStreamAdapter()), tx);
    }

    @Test
    void capabilitiesSeparateCascadeAndStreamAdaptersAndNeverForgeVerifiedInterop() {
        var caps = service().capabilities();
        assertEquals("platform-ingress-v1", caps.get("schema_version"));
        assertEquals(false, caps.get("interoperability_verified_default"));
        assertEquals(false, caps.get("allows_raw_url_from_client"));
        var adapters = (List<?>) caps.get("adapters");
        assertEquals(2, adapters.size());
    }

    @Test
    void rejectsRawCredentialUrlsAndUnverifiedClockOffsets() {
        var svc = service();
        var rawUrlReq = new VideoPlatformService.PlatformUpsert(
            UUID.randomUUID(), 0L, "gb28181_cascade", "市局级联WVP",
            "rtsp://admin:secret@10.0.0.5:554/stream", "zlm-01", "unverified", null, true
        );
        var rejectedUrl = assertThrows(AdminService.Rejected.class, () -> svc.upsertPlatform("wvp-01", rawUrlReq, "admin"));
        assertEquals("invalid_platform_endpoint", rejectedUrl.code);

        var fakeClockReq = new VideoPlatformService.PlatformUpsert(
            UUID.randomUUID(), 0L, "gb28181_cascade", "市局级联WVP",
            "env:WVP_MAIN_SECRET", "zlm-01", "unverified", 120L, false
        );
        var rejectedClock = assertThrows(AdminService.Rejected.class, () -> svc.upsertPlatform("wvp-01", fakeClockReq, "admin"));
        assertEquals("platform_clock_unverified", rejectedClock.code);
    }

    @Test
    void verifiedClockResolutionRequiresExplicitVerificationFlag() {
        Instant base = Instant.parse("2026-11-18T03:00:00Z");
        assertTrue(VideoPlatformAdapter.resolveVerifiedSourceTime(false, 500L, base).isEmpty());
        assertTrue(VideoPlatformAdapter.resolveVerifiedSourceTime(true, 500L, null).isEmpty());
        assertEquals(base.plusMillis(500L), VideoPlatformAdapter.resolveVerifiedSourceTime(true, 500L, base).orElseThrow());
    }
}
