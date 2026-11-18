package cn.trial.videosearch.ingress;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.investigation.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real PostgreSQL 17 integration contract for migration 036, WVP-PRO/ZLM channel mappings, sessions, and MOT association. */
class VideoPlatformPostgresIntegrationTest {
    private String schema, url, user, password;
    private JdbcTemplate db;
    private DriverManagerDataSource dataSource;
    private SearchOrchestrator scope;
    private VideoPlatformService platforms;

    @BeforeEach
    void prepare() throws Exception {
        url = System.getenv("MTMC_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "isolated PostgreSQL CI service not configured");
        user = System.getenv().getOrDefault("MTMC_TEST_JDBC_USER", "postgres");
        password = System.getenv().getOrDefault("MTMC_TEST_JDBC_PASSWORD", "");
        schema = "mtmc_contract_" + UUID.randomUUID().toString().replace("-", "");
        try (var conn = DriverManager.getConnection(url, user, password); var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
        }
        dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        db = new JdbcTemplate(dataSource);
        applyMigrations();
        db.update("INSERT INTO cameras(camera_id,display_name,enabled) VALUES('one','一号卡口',true),('two','二号卡口',true)");
        scope = mock(SearchOrchestrator.class);
        when(scope.readableCameras()).thenReturn(Set.of("one", "two"));
        when(scope.mayReadCamera(anyString())).thenAnswer(c -> Set.of("one", "two").contains(c.getArgument(0)));
        platforms = new VideoPlatformService(
            db, scope,
            List.of(new Gb28181CascadePlatformAdapter(), new PlatformStreamAdapter()),
            new DataSourceTransactionManager(dataSource)
        );
    }

    private void applyMigrations() throws Exception {
        Path root = Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource("db/schema.sql")).toURI()).getParent();
        var paths = new ArrayList<Path>();
        paths.add(root.resolve("schema.sql"));
        paths.add(root.resolve("migrations/002-observation-uncertainty.sql"));
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().matches("[0-9]{3}-.*\\.sql")).sorted().forEach(paths::add);
        }
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (var path : paths) stmt.execute(Files.readString(path));
            conn.commit();
        }
    }

    private void reapplyMigration036() throws Exception {
        Path root = Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource("db/schema.sql")).toURI()).getParent();
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            stmt.execute(Files.readString(root.resolve("036-video-platform-and-mot.sql")));
            conn.commit();
        }
    }

    @AfterEach
    void clean() throws Exception {
        if (schema == null) return;
        if (!schema.matches("mtmc_contract_[a-f0-9]{32}")) throw new IllegalStateException("invalid schema scope");
        try (var conn = DriverManager.getConnection(url, user, password); var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void platformChannelMappingSessionsAndMtmcLocatorLifecycle() throws Exception {
        // Replay migration 036 idempotently without re-running legacy unguarded ADD CONSTRAINT scripts
        reapplyMigration036();

        UUID platReq = UUID.randomUUID();
        var platRes = platforms.upsertPlatform("wvp-nj-01", new VideoPlatformService.PlatformUpsert(
            platReq, 0L, "gb28181_cascade", "南京WVP级联", "k8s-secret:wvp-nj-01", "zlm-edge-01",
            "unverified", null, true
        ), "admin");
        var plat = (Map<?, ?>) platRes.get("platform");
        assertEquals(1L, plat.get("revision"));
        assertEquals(false, plat.get("interoperability_verified"));
        assertEquals(platRes, platforms.upsertPlatform("wvp-nj-01", new VideoPlatformService.PlatformUpsert(
            platReq, 0L, "gb28181_cascade", "南京WVP级联", "k8s-secret:wvp-nj-01", "zlm-edge-01",
            "unverified", null, true
        ), "admin"));

        assertEquals("platform_revision_conflict", assertThrows(AdminService.Rejected.class, () ->
            platforms.upsertPlatform("wvp-nj-01", new VideoPlatformService.PlatformUpsert(
                UUID.randomUUID(), 0L, "gb28181_cascade", "冲突编辑", "k8s-secret:wvp-nj-01", "zlm-edge-01",
                "unverified", null, true
            ), "admin")).code);

        platforms.upsertChannelMapping("wvp-nj-01", new VideoPlatformService.ChannelMappingUpsert(
            UUID.randomUUID(), 0L, "34020000001320000001", "34020000001310000002",
            "two", "二号卡口国标通道", "main", false, null, true
        ), "admin");

        // Second channel pointing to the same active camera 'two' must fail with conflict
        assertEquals("platform_mapping_conflict", assertThrows(AdminService.Rejected.class, () ->
            platforms.upsertChannelMapping("wvp-nj-01", new VideoPlatformService.ChannelMappingUpsert(
                UUID.randomUUID(), 0L, "34020000001320000099", "34020000001310000099",
                "two", "冲突重复通道", "main", false, null, true
            ), "admin")).code);

        // Session acquisition & epoch monotonicity
        var s1 = (Map<?, ?>) platforms.acquireSession(new VideoPlatformService.AcquireSessionRequest(
            UUID.randomUUID(), "wvp-nj-01", "34020000001320000001", "34020000001310000002",
            "live", "main", "worker-1", 60, null, null
        )).get("session");
        assertEquals(0L, s1.get("media_epoch"));
        assertEquals("active", s1.get("state"));
        assertEquals("unverified", s1.get("clock_state"));
        UUID sid1 = UUID.fromString((String) s1.get("session_id"));
        UUID tok1 = UUID.fromString((String) s1.get("lease_token"));

        platforms.renewSession(sid1, new VideoPlatformService.RenewSessionRequest("worker-1", tok1, 120));
        platforms.releaseSession(sid1, new VideoPlatformService.ReleaseSessionRequest("worker-1", tok1, "completed"));

        var s2 = (Map<?, ?>) platforms.acquireSession(new VideoPlatformService.AcquireSessionRequest(
            UUID.randomUUID(), "wvp-nj-01", "34020000001320000001", "34020000001310000002",
            "live", "sub", "worker-1", 60, null, null
        )).get("session");
        assertEquals(1L, s2.get("media_epoch"));

        // Verify detected_targets accepts ByteTrack & OC-SORT associations under PostgreSQL constraints
        UUID srcId = UUID.randomUUID(), taskId = UUID.randomUUID(), frameId = UUID.randomUUID(), runId = UUID.randomUUID();
        db.update("INSERT INTO sources(source_id,camera_id,source_type,source_ref,source_time_origin,enabled) VALUES(?,'two','rtsp','wvp-nj-01:two','unknown',true)", srcId);
        db.update("INSERT INTO processing_tasks(task_id,source_id,task_type,state) VALUES(?,?,'rtsp','active')", taskId, srcId);
        db.update("INSERT INTO capture_outbox(frame_id,receipt,task_id,worker_id,lease_generation,evidence_ref,payload_sha256,camera_id,source_type,source_time_origin,media_offset_ms,source_time,scene_run_id,media_epoch,relative_media_ms,plan,admission_requested) VALUES(?,?,?,'w',1,'evidence:1',?,'two','rtsp','unknown',1000,NULL,?,1,1000,'{}'::jsonb,true)",
            frameId, UUID.randomUUID(), taskId, "b".repeat(64), runId);
        db.update("INSERT INTO detected_targets(target_id,frame_id,target_ordinal,object_type,class_name,confidence,bbox_xyxy,bbox_normalized,track_id,association) VALUES(?,?,0,'person','person',0.9,'[10,10,50,90]'::jsonb,'[0.1,0.1,0.5,0.9]'::jsonb,?,'ocsort_momentum_v1')",
            UUID.randomUUID(), frameId, UUID.randomUUID());

        // Verify InvestigationRepository.platformLocators + MtmcQueries locator hydration
        var repo = new InvestigationRepository(db, scope);
        var locators = repo.platformLocators(List.of("one", "two"));
        assertEquals(1, locators.size());
        assertEquals("34020000001310000002", locators.get("two").get("channel_id"));
    }
}
