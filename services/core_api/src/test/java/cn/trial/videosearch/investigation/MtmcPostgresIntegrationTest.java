package cn.trial.videosearch.investigation;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL persistence/lease contracts. No images, models or field identities. */
class MtmcPostgresIntegrationTest {
    private String schema,url,user,password;
    private JdbcTemplate db;
    private DriverManagerDataSource dataSource;
    private final InvestigationRepository observations=mock(InvestigationRepository.class);
    private final AppearanceCatalog catalog=mock(AppearanceCatalog.class);
    private final AppearanceIndex index=mock(AppearanceIndex.class);
    private final AppearanceSource sources=mock(AppearanceSource.class);
    private final MtmcFeatures features=mock(MtmcFeatures.class);
    private final ReviewedPlateFacts plates=mock(ReviewedPlateFacts.class);
    private final SearchOrchestrator scope=mock(SearchOrchestrator.class);
    private final String sha="a".repeat(64);
    private ObservationPoint anchor,candidate;
    private AppearanceCatalog.Profile profile;
    private MtmcQueries queries;

    @BeforeEach void prepare() throws Exception {
        url=System.getenv("MTMC_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null&&!url.isBlank(),"isolated PostgreSQL CI service not configured");
        user=System.getenv().getOrDefault("MTMC_TEST_JDBC_USER","postgres");
        password=System.getenv().getOrDefault("MTMC_TEST_JDBC_PASSWORD","");
        schema="mtmc_contract_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            statement.execute("CREATE SCHEMA "+schema);
        }
        dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        db=new JdbcTemplate(dataSource);
        applyMigrations();
        anchor=point("one");candidate=point("two");
        profile=new AppearanceCatalog.Profile("contract-model",sha,384,Map.of("profile","contract-model"));
        when(catalog.current()).thenReturn(new AppearanceCatalog.Snapshot(1,profile.id(),Map.of(profile.id(),profile)));
        when(scope.readableCameras()).thenReturn(Set.of("one","two"));
        when(scope.mayReadCamera(anyString())).thenAnswer(call->Set.of("one","two").contains(call.getArgument(0)));
        when(observations.anchor(any(UUID.class),any())).thenAnswer(call->{
            UUID id=call.getArgument(0);if(id.equals(anchor.observationId()))return anchor;
            if(id.equals(candidate.observationId()))return candidate;
            throw new InputRejected(404,"observation_unavailable");
        });
        when(observations.points(anyList())).thenReturn(List.of(candidate));
        when(sources.current(any(UUID.class),anyLong())).thenAnswer(call->source(call.getArgument(0).equals(anchor.observationId())?anchor:candidate));
        var vector=new ArrayList<Double>(Collections.nCopies(384,0.));vector.set(0,1.);
        when(features.current(any(),anyMap())).thenReturn(Optional.of(new MtmcFeatures.Feature(vector,sha,sha,sha)));
        when(features.clock(any())).thenReturn(Map.of("relative_media_ms",1000L));
        when(index.find(any(),anyList(),any(),anyInt())).thenReturn(found());
        queries=new MtmcQueries(db,observations,catalog,index,sources,features,plates,scope,
            List.of(new AppearanceMtmcStrategy(),new EvidenceFusionMtmcStrategy()),new DataSourceTransactionManager(dataSource),true,true);
    }

    private void applyMigrations() throws Exception {
        Path root=Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource("db/schema.sql")).toURI()).getParent();
        var paths=new ArrayList<Path>();paths.add(root.resolve("schema.sql"));paths.add(root.resolve("migrations/002-observation-uncertainty.sql"));
        try(var files=Files.list(root)){files.filter(path->path.getFileName().toString().matches("[0-9]{3}-.*\\.sql")).sorted().forEach(paths::add);}
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
            connection.setAutoCommit(false);
            for(var path:paths)statement.execute(Files.readString(path));
            connection.commit();
        }
    }

    @AfterEach void clean() throws Exception {
        if(schema==null)return;
        // Drop only the unique test-owned schema; never use public or a configured name.
        if(!schema.matches("mtmc_contract_[a-f0-9]{32}"))throw new IllegalStateException("invalid cleanup scope");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            statement.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }

    private ObservationPoint point(String camera){
        return new ObservationPoint(UUID.randomUUID(),1,UUID.randomUUID(),camera,"测试点位","person","行人",Map.of(),
            UUID.randomUUID(),UUID.randomUUID(),0L,1000L,null,UUID.randomUUID(),UUID.randomUUID(),"person",sha,null,null);
    }
    private Map<String,Object> source(ObservationPoint point){
        return Map.of("observation_id",point.observationId().toString(),"record_version",point.version(),
            "frame_id",point.frameId().toString(),"camera_id",point.cameraId(),"target_type","person");
    }
    private AppearanceIndex.Found found(){return new AppearanceIndex.Found(List.of(new AppearanceIndex.Ranked(source(candidate),
        Map.of("relative_media_ms",1000L),candidate.trackId().toString(),.8)),1,false);}
    private MtmcQueries.Query request(UUID id,int seconds){return new MtmcQueries.Query(id,anchor.observationId(),1L,
        "evidence-fusion-v1",List.of(profile.id()),List.of("two"),seconds,20);}

    @Test void persistsBothRunsWithUnknownTimeAndReusesSnapshotAfterHotSwitch() {
        UUID id=UUID.randomUUID();var first=queries.create(request(id,600),"contract-user",1);
        assertEquals(2,((List<?>)first.get("runs")).size());
        assertEquals(1,((List<?>)first.get("items")).size());
        var item=(Map<?,?>)((List<?>)first.get("items")).getFirst();
        assertEquals("unknown",item.get("temporal_relation"));assertEquals(false,item.get("identity_verified"));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM mtmc_queries WHERE state='completed'",Long.class));
        reset(catalog);
        var repeated=queries.create(request(id,600),"contract-user",1);
        assertEquals(first.get("selected_run"),repeated.get("selected_run"));verifyNoInteractions(catalog);
        verify(index,times(1)).find(any(),anyList(),any(),anyInt());
        assertEquals("mtmc_request_conflict",assertThrows(InputRejected.class,
            ()->queries.create(request(id,601),"contract-user",1)).code);
        assertEquals(404,assertThrows(InputRejected.class,()->queries.page(id,"another-user",1,0,20,null)).status);
        queries.close(id,"another-user",1);assertEquals(1L,db.queryForObject("SELECT count(*) FROM mtmc_queries",Long.class));
        queries.close(id,"contract-user",1);assertEquals(0L,db.queryForObject("SELECT count(*) FROM mtmc_queries",Long.class));
    }

    @Test void sharedAdmissionAndCancelFencingUseActualDatabaseConstraints() {
        for(int i=0;i<2;i++)db.update("INSERT INTO mtmc_queries(query_id,actor_id,actor_revision,request_sha256,scope_sha256,state,lease_token,lease_expires_at) VALUES(?,'reserved',1,?,?,'preparing',?,clock_timestamp()+interval '1 minute')",UUID.randomUUID(),sha,sha,UUID.randomUUID());
        assertEquals("mtmc_query_busy",assertThrows(InputRejected.class,()->queries.create(request(UUID.randomUUID(),600),"contract-user",1)).code);
        db.update("DELETE FROM mtmc_queries");
        UUID id=UUID.randomUUID();when(index.find(any(),anyList(),any(),anyInt())).thenAnswer(call->{
            queries.close(id,"contract-user",1);return found();
        });
        assertEquals("mtmc_query_cancelled",assertThrows(InputRejected.class,()->queries.create(request(id,600),"contract-user",1)).code);
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM mtmc_queries",Long.class));
    }

    @Test void allMigrationsReplayAndExpiredSnapshotsCannotBeRead() throws Exception {
        applyMigrations();UUID id=UUID.randomUUID();queries.create(request(id,600),"contract-user",1);
        db.update("UPDATE mtmc_queries SET expires_at=clock_timestamp()-interval '1 second' WHERE query_id=?",id);
        assertEquals(410,assertThrows(InputRejected.class,()->queries.page(id,"contract-user",1,0,20,null)).status);
    }
}
