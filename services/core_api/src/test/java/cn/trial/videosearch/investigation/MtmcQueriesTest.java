package cn.trial.videosearch.investigation;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MtmcQueriesTest {
    private static final String PROFILE="model",SHA="a".repeat(64);
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final InvestigationRepository observations=mock(InvestigationRepository.class);
    private final AppearanceCatalog catalog=mock(AppearanceCatalog.class);
    private final AppearanceIndex index=mock(AppearanceIndex.class);
    private final AppearanceSource sources=mock(AppearanceSource.class);
    private final MtmcFeatures features=mock(MtmcFeatures.class);
    private final ReviewedPlateFacts plates=mock(ReviewedPlateFacts.class);
    private final SearchOrchestrator scope=mock(SearchOrchestrator.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final UUID queryId=UUID.randomUUID();
    private final Map<UUID,ObservationPoint> points=new HashMap<>();
    private final ObservationPoint anchor=point("a");
    private MtmcQueries service(){return service(true,true);}
    private MtmcQueries service(boolean investigation,boolean mtmc){
        when(scope.readableCameras()).thenReturn(Set.of("a","b"));when(scope.mayReadCamera(anyString())).thenAnswer(call->Set.of("a","b").contains(call.getArgument(0)));
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(observations.anchor(any(UUID.class),any())).thenAnswer(call->{var point=points.get(call.getArgument(0));if(point==null)throw new InputRejected(404,"observation_unavailable");return point;});
        return new MtmcQueries(db,observations,catalog,index,sources,features,plates,scope,List.of(new AppearanceMtmcStrategy(),new EvidenceFusionMtmcStrategy()),manager,investigation,mtmc);
    }
    private ObservationPoint point(String camera){
        var point=new ObservationPoint(UUID.randomUUID(),1,UUID.randomUUID(),camera,"点位"+camera,"person","黑衣行人",Map.of("upper_color","黑色"),UUID.randomUUID(),UUID.randomUUID(),0L,1000L,null,UUID.randomUUID(),UUID.randomUUID(),"person",SHA,null,null);
        points.put(point.observationId(),point);return point;
    }
    private static Map<String,Object> source(ObservationPoint point){return Map.of("observation_id",point.observationId().toString(),"record_version",point.version(),"frame_id",point.frameId().toString(),"camera_id",point.cameraId(),"target_type",point.targetType());}
    private Map<String,Object> item(ObservationPoint point){
        var shot=new MtmcTracklets.Shot(new AppearanceIndex.Ranked(source(point),Map.of("relative_media_ms",1000),point.trackId().toString(),.8),point,Map.of("state","disabled"));
        var anchorShot=new MtmcTracklets.Shot(new AppearanceIndex.Ranked(source(anchor),Map.of("relative_media_ms",1000),anchor.trackId().toString(),1),anchor,Map.of("state","disabled"));
        return MtmcTracklets.freeze(new MtmcTracklets.Segment(List.of(anchorShot)),new MtmcTracklets.Segment(List.of(shot)),new EvidenceFusionMtmcStrategy(),600);
    }
    private Map<String,Object> stored(List<Map<String,Object>> items){
        var summary=new LinkedHashMap<String,Object>();summary.put("anchor",anchor.view());summary.put("selected_run",PROFILE+":evidence-fusion-v1");summary.put("_anchor_sources",List.of(source(anchor)));summary.put("_anchor_points",List.of(anchor.view()));summary.put("_features",List.of());summary.put("_anchor_plates",List.of());
        var run=new LinkedHashMap<String,Object>();run.put("id",PROFILE+":evidence-fusion-v1");run.put("profile",PROFILE);run.put("profile_sha256",SHA);run.put("strategy","evidence-fusion-v1");run.put("state","completed");run.put("elapsed_ms",1);run.put("total_candidate_periods",items.size());run.put("items",items);
        var row=new HashMap<String,Object>();row.put("actor_id","alice");row.put("actor_revision",3L);row.put("alive",true);row.put("state","completed");row.put("scope_sha256",CanonicalJson.encode(List.of("a","b"),8192).sha256());row.put("summary_text",CanonicalJson.encode(summary,262144).payload());row.put("runs_text",CanonicalJson.encode(List.of(run),1048576).payload());row.put("expires_at",Timestamp.from(Instant.now().plusSeconds(60)));return row;
    }
    private void serve(Map<String,Object> row){when(db.queryForList(startsWith("SELECT *,summary::text"),eq(queryId))).thenReturn(List.of(row));}
    @Test void frozenSnapshotSurvivesModelHotSwitchAndKeepsOnlyItsOwnRun(){
        var row=stored(List.of(item(point("b"))));serve(row);var result=service().page(queryId,"alice",3,0,20,null);
        assertEquals("mtmc-results-v1",result.get("schema_version"));assertEquals(PROFILE+":evidence-fusion-v1",result.get("selected_run"));assertEquals(1,((List<?>)result.get("items")).size());
        assertFalse(((Map<?,?>)result.get("summary")).keySet().stream().anyMatch(key->key.toString().startsWith("_")));
        assertFalse(((Map<?,?>)((List<?>)result.get("runs")).getFirst()).containsKey("items"));verifyNoInteractions(catalog,index);
    }
    @Test void pageRejectsOtherActorCredentialRevisionExpiryAndScopeChanges(){
        var row=stored(List.of());serve(row);var service=service();
        assertEquals(404,assertThrows(InputRejected.class,()->service.page(queryId,"bob",3,0,20,null)).status);
        assertEquals(404,assertThrows(InputRejected.class,()->service.page(queryId,"alice",4,0,20,null)).status);
        row.put("alive",false);assertEquals(410,assertThrows(InputRejected.class,()->service.page(queryId,"alice",3,0,20,null)).status);
        row.put("alive",true);row.put("scope_sha256","changed");assertEquals(409,assertThrows(InputRejected.class,()->service.page(queryId,"alice",3,0,20,null)).status);
        verifyNoInteractions(sources,index);
    }
    @Test void changedCandidateIsRemovedAsWholePeriodWithoutChangingFrozenRanks(){
        var stale=point("b");var good=point("b");var next=point("b");var row=stored(List.of(item(stale),item(good),item(next)));serve(row);
        doThrow(new InputRejected(409,"appearance_source_changed")).when(sources).verify(argThat(value->stale.observationId().toString().equals(value.get("observation_id"))));
        var result=service().page(queryId,"alice",3,0,1,null);
        assertEquals(1,result.get("removed_stale_periods"));assertEquals(true,result.get("has_more"));assertEquals(2,result.get("next_offset"));
        assertEquals(good.observationId().toString(),((Map<?,?>)((Map<?,?>)((List<?>)result.get("items")).getFirst()).get("representative")).get("observation_id"));
    }
    @Test void changedAnchorRejectsQueryAndDoesNotReturnAnyOldCandidate(){
        serve(stored(List.of(item(point("b")))));doThrow(new InputRejected(409,"appearance_source_changed")).when(sources).verify(argThat(value->anchor.observationId().toString().equals(value.get("observation_id"))));
        assertEquals(409,assertThrows(InputRejected.class,()->service().page(queryId,"alice",3,0,20,null)).status);
    }
    @Test void changedConfirmedPlateRemovesCandidateRatherThanServingOldEvidence(){
        var target=point("b");var item=item(target);var shots=(List<Map<String,Object>>)item.get("sightings");var shot=new LinkedHashMap<>(shots.getFirst());shot.put("plate",Map.of("state","ready","sha256",SHA));item.put("sightings",List.of(shot));serve(stored(List.of(item)));
        when(plates.resolve(target.observationId())).thenReturn(new ReviewedPlateFacts.Snapshot(target.observationId(),1,target.frameId(),"b",SHA,"empty",null,List.of(),"b".repeat(64)));
        var result=service().page(queryId,"alice",3,0,20,null);assertTrue(((List<?>)result.get("items")).isEmpty());assertEquals(1,result.get("removed_stale_periods"));
    }
    @Test void reassignedLocalTrackInvalidatesFrozenPeriodEvenIfObservationVersionDidNotChange(){
        var target=point("b");serve(stored(List.of(item(target))));
        points.put(target.observationId(),new ObservationPoint(target.observationId(),target.version(),target.frameId(),target.cameraId(),target.cameraName(),target.targetType(),target.description(),target.attributes(),target.taskId(),target.runId(),target.epoch(),target.mediaMs(),target.sourceTime(),UUID.randomUUID(),target.targetId(),target.className(),target.evidenceSha(),target.longitude(),target.latitude()));
        var result=service().page(queryId,"alice",3,0,20,null);assertTrue(((List<?>)result.get("items")).isEmpty());assertEquals(1,result.get("removed_stale_periods"));
    }
    @Test void createProducesIndependentStrategiesAndUnknownTimeCandidatesWithoutSwitchingModels(){
        var service=service();var target=point("b");var state=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        when(db.queryForList(startsWith("SELECT *,summary::text"),eq(queryId))).thenAnswer(call->state.get()==null?List.of():List.of(state.get()));
        when(db.queryForObject(anyString(),eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(contains("actor_id=?"),eq(Long.class),eq("alice"))).thenReturn(0L);
        when(db.update(startsWith("INSERT INTO mtmc_queries"),any(Object[].class))).thenAnswer(call->{
            Object[] args=(Object[])call.getRawArguments()[1];var row=new HashMap<String,Object>();row.put("actor_id",args[1]);row.put("actor_revision",args[2]);row.put("request_sha256",args[3]);row.put("scope_sha256",args[4]);row.put("state","preparing");row.put("alive",true);row.put("lease_alive",true);row.put("expires_at",Timestamp.from(Instant.now().plusSeconds(1800)));state.set(row);return 1;
        });
        when(db.update(startsWith("UPDATE mtmc_queries SET state='completed'"),any(Object[].class))).thenAnswer(call->{
            Object[] args=(Object[])call.getRawArguments()[1];state.get().put("summary_text",args[0]);state.get().put("runs_text",args[1]);state.get().put("state","completed");return 1;
        });
        var profile=new AppearanceCatalog.Profile(PROFILE,SHA,2,Map.of("profile",PROFILE));
        when(catalog.current()).thenReturn(new AppearanceCatalog.Snapshot(7,PROFILE,Map.of(PROFILE,profile)));
        when(sources.current(anchor.observationId(),anchor.version())).thenReturn(source(anchor));
        when(features.current(eq(profile),anyMap())).thenReturn(Optional.of(new MtmcFeatures.Feature(List.of(1.,0.),"source",SHA,SHA)));
        when(features.clock(any(UUID.class))).thenReturn(Map.of("relative_media_ms",1000L));
        when(observations.points(anyCollection())).thenReturn(List.of(target));
        when(index.find(eq(profile),anyList(),any(AppearanceIndex.Filter.class),eq(200))).thenReturn(new AppearanceIndex.Found(List.of(new AppearanceIndex.Ranked(source(target),Map.of("relative_media_ms",1000L),target.trackId().toString(),.8)),1,false));
        var result=service.create(new MtmcQueries.Query(queryId,anchor.observationId(),1L,"evidence-fusion-v1",List.of(PROFILE),List.of("b"),600,20),"alice",3);
        assertEquals(2,((List<?>)result.get("runs")).size());assertEquals(1,((List<?>)result.get("items")).size());
        assertEquals("unknown",((Map<?,?>)((List<?>)result.get("items")).getFirst()).get("temporal_relation"));
        assertEquals("single_frame",((Map<?,?>)((List<?>)result.get("runs")).getFirst()).get("anchor_feature_mode"));
        verify(index,times(1)).find(eq(profile),anyList(),any(AppearanceIndex.Filter.class),eq(200));
        verify(db,never()).update(contains("managed_model_catalogs"),any(Object[].class));
    }
    @Test void pagesRejectMissingRunsAndOutOfBoundsWithoutCreatingNewQueries(){
        serve(stored(List.of()));var service=service();assertThrows(IllegalArgumentException.class,()->service.page(queryId,"alice",3,0,20,"other:appearance-v1"));
        assertThrows(IllegalArgumentException.class,()->service.page(queryId,"alice",3,1,20,null));assertThrows(IllegalArgumentException.class,()->service.page(queryId,"alice",3,0,51,null));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void admissionIsSharedAndBoundedBeforeAnyFeatureOrModelAccess(){
        var service=service();when(db.queryForList(startsWith("SELECT *,summary::text"),eq(queryId))).thenReturn(List.of());
        when(db.queryForObject(contains("state='preparing' AND lease_expires_at"),eq(Long.class))).thenReturn(2L);
        var request=new MtmcQueries.Query(queryId,anchor.observationId(),1L,"appearance-v1",List.of(PROFILE),List.of("b"),600,20);
        var error=assertThrows(InputRejected.class,()->service.create(request,"alice",3));assertEquals(429,error.status);assertEquals("mtmc_query_busy",error.code);verifyNoInteractions(features,index,catalog);
    }
    @Test void preparingDuplicateRequestCannotStartSecondModelScan(){
        var row=stored(List.of());row.put("state","preparing");row.put("lease_alive",true);row.put("request_sha256",fingerprint());serve(row);
        var request=new MtmcQueries.Query(queryId,anchor.observationId(),1L,"appearance-v1",List.of(PROFILE),List.of("b"),600,20);
        var error=assertThrows(InputRejected.class,()->service().create(request,"alice",3));assertEquals("mtmc_query_running",error.code);verifyNoInteractions(index,catalog);
    }
    private String fingerprint(){
        return CanonicalJson.encode(Map.of("observation_id",anchor.observationId().toString(),"expected_version",1L,"strategy","appearance-v1","profiles",List.of(PROFILE),"cameras",List.of("b"),"seconds",600),16384).sha256();
    }
    @Test void createValidatesCameraScopeAndProfileCountBeforeReservation(){
        var service=service();var request=new MtmcQueries.Query(queryId,anchor.observationId(),1L,"appearance-v1",List.of(PROFILE),List.of("forbidden"),600,20);
        assertEquals(403,assertThrows(InputRejected.class,()->service.create(request,"alice",3)).status);
        var tooMany=new MtmcQueries.Query(queryId,anchor.observationId(),1L,"appearance-v1",List.of("a","b","c","d"),List.of(),600,20);
        assertThrows(IllegalArgumentException.class,()->service.create(tooMany,"alice",3));verifyNoInteractions(db,index,catalog);
    }
    @Test void closeIsIdempotentAndRestrictedToSnapshotOwnerAndCredentialRevision(){
        assertEquals(Map.of("closed",true),service().close(queryId,"alice",3));
        verify(db).update("DELETE FROM mtmc_queries WHERE query_id=? AND actor_id=? AND actor_revision=?",queryId,"alice",3L);
    }
    @Test void disabledFeatureStopsNewQueriesAndSnapshotReads(){
        var service=service(true,false);assertEquals(404,assertThrows(InputRejected.class,()->service.page(queryId,"alice",3,0,20,null)).status);
        assertEquals(false,service.capabilities().get("enabled"));verifyNoInteractions(index,features);
    }
    @Test void expiredSnapshotsAndAbandonedLeasesHaveBoundedCleanupEvenWhenFeatureIsOff(){
        service(false,false).cleanupExpired();
        verify(db).update("DELETE FROM mtmc_queries WHERE query_id IN (SELECT query_id FROM mtmc_queries WHERE expires_at<clock_timestamp() OR state='preparing' AND lease_expires_at<clock_timestamp() ORDER BY created_at LIMIT 200)");
        verifyNoInteractions(index,features,catalog);
    }
}
