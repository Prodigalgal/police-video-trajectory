package cn.trial.videosearch.investigation;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Query-driven cross-camera candidates, with immutable model/policy and bounded shared admission. */
@Service
public class MtmcQueries {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int CANDIDATES=200,MAX_PROFILES=3,MAX_ANCHOR_FEATURES=5,MAX_PAGE=50;
    private static final long BUDGET_NANOS=300_000_000_000L;
    private final JdbcTemplate db;private final InvestigationRepository observations;private final AppearanceCatalog catalog;
    private final AppearanceIndex index;private final AppearanceSource sources;private final MtmcFeatures features;
    private final ReviewedPlateFacts plates;private final SearchOrchestrator permissions;private final Map<String,MtmcStrategy> strategies;
    private final TransactionTemplate transaction;private final boolean enabled;
    public MtmcQueries(JdbcTemplate db,InvestigationRepository observations,AppearanceCatalog catalog,AppearanceIndex index,AppearanceSource sources,MtmcFeatures features,
                       ReviewedPlateFacts plates,SearchOrchestrator permissions,List<MtmcStrategy> strategies,PlatformTransactionManager manager,
                       @Value("${VIDEO_INVESTIGATION_ENABLED:false}")boolean investigationEnabled,@Value("${VIDEO_MTMC_ENABLED:true}")boolean enabled){
        this.db=db;this.observations=observations;this.catalog=catalog;this.index=index;this.sources=sources;this.features=features;this.plates=plates;this.permissions=permissions;
        this.strategies=strategies.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(MtmcStrategy::id,java.util.function.Function.identity()));
        if(this.strategies.isEmpty()||this.strategies.size()>8||this.strategies.keySet().stream().anyMatch(id->!id.matches("[A-Za-z0-9_-]{1,80}")))throw new IllegalArgumentException("Invalid MTMC strategy registry");
        transaction=new TransactionTemplate(manager);transaction.setTimeout(10);this.enabled=investigationEnabled&&enabled;
    }
    public record Query(UUID requestId,UUID observationId,Long expectedVersion,String strategy,List<String> profiles,List<String> cameraIds,Integer seconds,Integer limit){}
    private void requireEnabled(){if(!enabled)throw new InputRejected(404,"mtmc_disabled");}
    private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException();return (Map<String,Object>)map;}
    private static List<Map<String,Object>> objects(Object value){if(!(value instanceof List<?> list))throw new IllegalArgumentException();return list.stream().map(MtmcQueries::object).toList();}
    private String scope(){return CanonicalJson.encode(permissions.readableCameras().stream().sorted().toList(),8192).sha256();}
    private List<Map<String,Object>> cameras(){
        var allowed=permissions.readableCameras().stream().sorted().toList();if(allowed.isEmpty())return List.of();
        return db.queryForList("SELECT camera_id,display_name FROM cameras WHERE camera_id IN ("+String.join(",",Collections.nCopies(allowed.size(),"?"))+") ORDER BY display_name,camera_id",allowed.toArray());
    }
    private static List<String> targetTypes(AppearanceCatalog.Profile profile){
        Object raw=profile.definition().get("target_types");if(raw==null)return List.of("person","vehicle");
        if(!(raw instanceof List<?> list)||list.isEmpty()||list.stream().anyMatch(value->!Set.of("person","vehicle").contains(value)))throw new InputRejected(503,"appearance_catalog_unavailable");
        return list.stream().map(String.class::cast).toList();
    }
    public Map<String,Object> capabilities(){
        var value=new LinkedHashMap<String,Object>();value.put("schema_version","mtmc-capabilities-v1");value.put("enabled",enabled&&catalog.configured());
        value.put("strategies",strategies.values().stream().sorted(Comparator.comparing(MtmcStrategy::id)).map(strategy->Map.of("id",strategy.id(),"label",strategy.label(),"parameters",strategy.parameters())).toList());
        value.put("cameras",cameras());value.put("maximum_profiles",MAX_PROFILES);value.put("maximum_candidates",CANDIDATES);value.put("maximum_anchor_features",MAX_ANCHOR_FEATURES);
        value.put("identity_verified",false);value.put("geography_state","camera_topology_unverified");value.put("continuous_online_tracking",false);
        if(!enabled||!catalog.configured()){value.put("profiles",List.of());value.put("active_by_type",Map.of());return value;}
        var snapshot=catalog.current();var models=new ArrayList<Map<String,Object>>();
        for(var profile:snapshot.profiles().values().stream().sorted(Comparator.comparing(AppearanceCatalog.Profile::id)).toList()){
            var item=new LinkedHashMap<String,Object>();item.put("id",profile.id());item.put("label",profile.id());item.put("sha256",profile.sha256());item.put("target_types",targetTypes(profile));item.put("definition",profile.definition());models.add(item);
        }
        value.put("profiles",models);value.put("catalog_revision",snapshot.revision());value.put("active_by_type",Map.of("person",snapshot.selected("person").id(),"vehicle",snapshot.selected("vehicle").id()));return value;
    }
    private List<Map<String,Object>> stored(UUID id){return db.queryForList("SELECT *,summary::text AS summary_text,runs::text AS runs_text,expires_at>clock_timestamp() AS alive,lease_expires_at>clock_timestamp() AS lease_alive FROM mtmc_queries WHERE query_id=?",id);}
    private static void check(Map<String,Object> row,String actor,long revision,String fingerprint,String scope){
        if(!actor.equals(row.get("actor_id"))||revision!=((Number)row.get("actor_revision")).longValue())throw new InputRejected(404,"mtmc_query_unavailable");
        if(!Boolean.TRUE.equals(row.get("alive")))throw new InputRejected(410,"mtmc_query_expired");
        if(!scope.equals(row.get("scope_sha256")))throw new InputRejected(409,"mtmc_scope_changed");
        if(fingerprint!=null&&!fingerprint.equals(row.get("request_sha256")))throw new InputRejected(409,"mtmc_request_conflict");
    }
    private boolean reserve(Query query,String actor,long revision,String fingerprint,String scope,UUID lease){
        return transaction.execute(status->{
            db.execute("SELECT pg_advisory_xact_lock(918273653)");var old=stored(query.requestId());
            if(!old.isEmpty()){
                var row=old.getFirst();check(row,actor,revision,fingerprint,scope);
                if("completed".equals(row.get("state")))return false;
                if(Boolean.TRUE.equals(row.get("lease_alive")))throw new InputRejected(409,"mtmc_query_running");
                db.update("DELETE FROM mtmc_queries WHERE query_id=? AND state='preparing'",query.requestId());
            }
            db.update("DELETE FROM mtmc_queries WHERE query_id IN (SELECT query_id FROM mtmc_queries WHERE expires_at<clock_timestamp() OR state='preparing' AND lease_expires_at<clock_timestamp() ORDER BY created_at LIMIT 200)");
            if(db.queryForObject("SELECT count(*) FROM mtmc_queries WHERE state='preparing' AND lease_expires_at>clock_timestamp()",Long.class)>=2)throw new InputRejected(429,"mtmc_query_busy");
            if(db.queryForObject("SELECT count(*) FROM mtmc_queries WHERE actor_id=? AND expires_at>clock_timestamp()",Long.class,actor)>=10||db.queryForObject("SELECT count(*) FROM mtmc_queries WHERE expires_at>clock_timestamp()",Long.class)>=100)throw new InputRejected(429,"mtmc_query_limit");
            db.update("INSERT INTO mtmc_queries(query_id,actor_id,actor_revision,request_sha256,scope_sha256,state,lease_token,lease_expires_at) VALUES(?,?,?,?,?,'preparing',?,clock_timestamp()+interval '360 seconds')",query.requestId(),actor,revision,fingerprint,scope,lease);return true;
        });
    }
    private static void budget(long deadline){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new InputRejected(503,"mtmc_query_timeout");}
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void cleanupExpired(){
        db.update("DELETE FROM mtmc_queries WHERE query_id IN (SELECT query_id FROM mtmc_queries WHERE expires_at<clock_timestamp() OR state='preparing' AND lease_expires_at<clock_timestamp() ORDER BY created_at LIMIT 200)");
    }
    private Map<String,Object> plate(ObservationPoint point,Map<String,Object> source,Map<UUID,Map<String,Object>> cache){
        if(!"vehicle".equals(point.targetType())||!plates.enabled())return Map.of("state","disabled");
        return cache.computeIfAbsent(point.observationId(),ignored->{
            var fact=plates.resolve(point.observationId());
            if(fact.version()!=point.version()||!Objects.equals(fact.frame(),point.frameId())||!Objects.equals(fact.evidence(),source.get("image_sha256")))throw new InputRejected(409,"mtmc_source_changed");
            var value=new LinkedHashMap<String,Object>();value.put("state",fact.outcome());value.put("number",fact.number());value.put("sha256",fact.sha256());return value;
        });
    }
    private record AnchorContext(List<ObservationPoint> points,boolean truncated){}
    private AnchorContext anchorPoints(ObservationPoint anchor){
        var points=new ArrayList<ObservationPoint>();points.add(anchor);if(!anchor.localClock()||anchor.trackId()==null)return new AnchorContext(List.copyOf(points),false);
        var neighbors=observations.trackNeighbors(anchor,MtmcTracklets.MAX_GAP_MS,MAX_ANCHOR_FEATURES);
        neighbors.stream().filter(point->!point.observationId().equals(anchor.observationId())&&point.localScope().equals(anchor.localScope())&&Objects.equals(point.trackId(),anchor.trackId())&&Objects.equals(point.className(),anchor.className())&&Math.abs(point.mediaMs()-anchor.mediaMs())<=MtmcTracklets.MAX_GAP_MS)
            .limit(MAX_ANCHOR_FEATURES-1).forEach(points::add);return new AnchorContext(List.copyOf(points),neighbors.size()>MAX_ANCHOR_FEATURES-1);
    }
    private static Map<String,Object> run(AppearanceCatalog.Profile profile,MtmcStrategy strategy,String state,String label,long millis,int scanned,boolean truncated,List<Map<String,Object>> items,int anchorFeatures){
        var run=new LinkedHashMap<String,Object>();run.put("id",profile.id()+":"+strategy.id());run.put("profile",profile.id());run.put("profile_sha256",profile.sha256());run.put("strategy",strategy.id());run.put("strategy_label",strategy.label());run.put("parameters",strategy.parameters());
        run.put("state",state);run.put("label",label);run.put("elapsed_ms",millis);run.put("scanned",scanned);run.put("truncated",truncated);run.put("total_candidate_periods",items.size());run.put("anchor_feature_count",anchorFeatures);run.put("anchor_feature_mode",anchorFeatures==0?"unavailable":anchorFeatures>1?"local_track_centroid":"single_frame");run.put("items",items);return run;
    }
    public Map<String,Object> create(Query query,String actor,long revision){
        requireEnabled();if(query==null||query.requestId()==null||query.observationId()==null||actor==null||actor.isBlank()||revision<1||query.expectedVersion()!=null&&query.expectedVersion()<1)throw new IllegalArgumentException();
        int seconds=query.seconds()==null?600:query.seconds(),limit=query.limit()==null?20:query.limit();String strategy=query.strategy()==null?"evidence-fusion-v1":query.strategy();
        if(seconds<1||seconds>3600||limit<1||limit>MAX_PAGE||!strategies.containsKey(strategy))throw new IllegalArgumentException();
        var requestedProfiles=query.profiles()==null?List.<String>of():List.copyOf(query.profiles());
        if(requestedProfiles.size()>MAX_PROFILES||new HashSet<>(requestedProfiles).size()!=requestedProfiles.size()||requestedProfiles.stream().anyMatch(id->!id.matches("[A-Za-z0-9_-]{1,80}")))throw new IllegalArgumentException();
        var requestedCameras=query.cameraIds()==null?List.<String>of():List.copyOf(query.cameraIds());
        if(requestedCameras.size()>50||new HashSet<>(requestedCameras).size()!=requestedCameras.size()||requestedCameras.stream().anyMatch(camera->!permissions.mayReadCamera(camera)))throw new InputRejected(403,"mtmc_scope_forbidden");
        var fingerprintValue=new LinkedHashMap<String,Object>();fingerprintValue.put("observation_id",query.observationId().toString());fingerprintValue.put("expected_version",query.expectedVersion());fingerprintValue.put("strategy",strategy);fingerprintValue.put("profiles",requestedProfiles);fingerprintValue.put("cameras",requestedCameras.stream().sorted().toList());fingerprintValue.put("seconds",seconds);
        String fingerprint=CanonicalJson.encode(fingerprintValue,16384).sha256(),scope=scope();UUID lease=UUID.randomUUID();
        if(!reserve(query,actor,revision,fingerprint,scope,lease))return page(query.requestId(),actor,revision,0,limit,null);
        try{
            long deadline=System.nanoTime()+BUDGET_NANOS;var anchor=observations.anchor(query.observationId(),query.expectedVersion());var snapshot=catalog.current();
            var profileIds=requestedProfiles.isEmpty()?List.of(snapshot.selected(anchor.targetType()).id()):requestedProfiles;
            var selectedProfiles=new ArrayList<AppearanceCatalog.Profile>();for(String id:profileIds){var profile=snapshot.profiles().get(id);if(profile==null||!targetTypes(profile).contains(anchor.targetType()))throw new InputRejected(422,"mtmc_profile_unavailable");selectedProfiles.add(profile);}
            var cameras=(requestedCameras.isEmpty()?permissions.readableCameras().stream():requestedCameras.stream()).filter(camera->!camera.equals(anchor.cameraId())).sorted().toList();
            var anchorContext=anchorPoints(anchor);var anchorPoints=anchorContext.points();var anchorSources=new LinkedHashMap<UUID,Map<String,Object>>();
            for(var point:anchorPoints){try{anchorSources.put(point.observationId(),sources.current(point.observationId(),point.version()));}catch(InputRejected changed){if(point.observationId().equals(anchor.observationId())||changed.status!=404&&changed.status!=409)throw changed;}}
            var plateCache=new HashMap<UUID,Map<String,Object>>();var frozenFeatures=new ArrayList<Map<String,Object>>();var runs=new ArrayList<Map<String,Object>>();
            for(var profile:selectedProfiles){
                budget(deadline);long started=System.nanoTime();var vectors=new ArrayList<MtmcFeatures.Feature>();var anchorShots=new ArrayList<MtmcTracklets.Shot>();boolean anchorMissing=false;
                for(var point:anchorPoints){var source=anchorSources.get(point.observationId());if(source==null)continue;var current=features.current(profile,source);
                    if(current.isEmpty()){if(point.observationId().equals(anchor.observationId()))anchorMissing=true;continue;}
                    var feature=current.get();vectors.add(feature);frozenFeatures.add(Map.of("profile",profile.id(),"profile_sha256",profile.sha256(),"dimensions",profile.dimensions(),"definition",profile.definition(),"source",source,"vector_sha256",feature.vectorSha256(),"content_sha256",feature.contentSha256()));
                    var hit=new AppearanceIndex.Ranked(source,features.clock(point.frameId()),Objects.toString(point.trackId(),""),1);anchorShots.add(new MtmcTracklets.Shot(hit,point,plate(point,source,plateCache)));
                }
                if(anchorMissing||vectors.isEmpty()){
                    for(var policy:strategies.values().stream().sorted(Comparator.comparing(MtmcStrategy::id)).toList())runs.add(run(profile,policy,"feature_missing","该模型尚未生成锚点特征，请先建立对应索引",(System.nanoTime()-started)/1_000_000,0,false,List.of(),0));continue;
                }
                try{
                    var representation=MtmcFeatures.representation(vectors,profile.dimensions());
                    var found=cameras.isEmpty()?new AppearanceIndex.Found(List.of(),0,false):index.find(profile,representation.vector(),new AppearanceIndex.Filter(anchor.targetType(),cameras,null,null),CANDIDATES);
                    budget(deadline);var ids=found.candidates().stream().map(hit->UUID.fromString((String)hit.source().get("observation_id"))).distinct().toList();
                    var currentPoints=new HashMap<UUID,ObservationPoint>();observations.points(ids).forEach(point->currentPoints.put(point.observationId(),point));var shots=new ArrayList<MtmcTracklets.Shot>();int invalid=0;
                    for(var hit:found.candidates()){
                        budget(deadline);var point=currentPoints.get(UUID.fromString((String)hit.source().get("observation_id")));
                        if(point==null||point.version()!=((Number)hit.source().get("record_version")).longValue()||point.cameraId().equals(anchor.cameraId())){invalid++;continue;}
                        try{sources.verify(hit.source());shots.add(new MtmcTracklets.Shot(hit,point,plate(point,hit.source(),plateCache)));}
                        catch(InputRejected changed){if(changed.status!=404&&changed.status!=409)throw changed;invalid++;}
                    }
                    var anchorSegment=new MtmcTracklets.Segment(List.copyOf(anchorShots));var segments=MtmcTracklets.group(shots);long retrievalMillis=(System.nanoTime()-started)/1_000_000;
                    for(var policy:strategies.values().stream().sorted(Comparator.comparing(MtmcStrategy::id)).toList()){
                        long ranked=System.nanoTime();var items=segments.stream().filter(segment->MtmcTracklets.withinWindow(anchorSegment,segment,seconds)).map(segment->MtmcTracklets.freeze(anchorSegment,segment,policy,seconds)).sorted(Comparator.<Map<String,Object>>comparingDouble(item->-((Number)item.get("rank_score")).doubleValue()).thenComparing(item->(String)item.get("segment_id"))).toList();
                        var result=run(profile,policy,"completed",items.isEmpty()?"未找到当前范围内的跨镜候选":"关联候选已生成",(System.nanoTime()-started)/1_000_000,found.scanned(),found.truncated(),items,vectors.size());
                        result.put("anchor_feature_mode",representation.mode());result.put("retrieval_elapsed_ms",retrievalMillis);result.put("ranking_elapsed_ms",(System.nanoTime()-ranked)/1_000_000);result.put("removed_stale_sightings",invalid);result.put("retained_temporal_unknown",items.stream().filter(item->"unknown".equals(item.get("temporal_relation"))).count());runs.add(result);
                    }
                }catch(InputRejected failure){
                    if(failure.status!=422&&failure.status!=503)throw failure;
                    for(var policy:strategies.values().stream().sorted(Comparator.comparing(MtmcStrategy::id)).toList()){
                        var failed=run(profile,policy,"unavailable",failure.status==422?"检索范围过大，请缩小点位范围":"该模型检索暂不可用，请稍后重试",(System.nanoTime()-started)/1_000_000,0,false,List.of(),vectors.size());failed.put("code",failure.code);runs.add(failed);
                    }
                }
            }
            budget(deadline);var anchorView=new LinkedHashMap<>(anchor.view());anchorView.put("media_offset_ms",features.clock(anchor.frameId()).get("media_offset_ms"));
            var summary=new LinkedHashMap<String,Object>();summary.put("anchor",anchorView);summary.put("catalog_revision",snapshot.revision());summary.put("selected_run",profileIds.getFirst()+":"+strategy);summary.put("cameras",cameras);summary.put("seconds",seconds);summary.put("candidate_limit",CANDIDATES);summary.put("maximum_anchor_features",MAX_ANCHOR_FEATURES);summary.put("maximum_gap_ms",MtmcTracklets.MAX_GAP_MS);summary.put("identity_verified",false);summary.put("source_time_available",anchor.sourceTime()!=null);summary.put("time_filter","verified_times_only_unknown_times_retained");summary.put("retrieval_order","bounded_visual_recall_then_time_window");summary.put("geography_state","camera_topology_unverified");summary.put("platform_locator_available",false);summary.put("_anchor_sources",new ArrayList<>(anchorSources.values()));summary.put("_anchor_points",anchorPoints.stream().filter(point->anchorSources.containsKey(point.observationId())).map(ObservationPoint::view).toList());summary.put("_features",frozenFeatures);summary.put("_anchor_plates",anchorPoints.stream().filter(point->plateCache.containsKey(point.observationId())).map(point->Map.of("observation_id",point.observationId().toString(),"sha256",plateCache.get(point.observationId()).get("sha256"))).toList());
            summary.put("anchor_context_truncated",anchorContext.truncated());
            String encodedSummary=CanonicalJson.encode(summary,256*1024).payload(),encodedRuns=CanonicalJson.encode(runs,8*1024*1024).payload();
            transaction.executeWithoutResult(status->{
                int changed=db.update("UPDATE mtmc_queries SET state='completed',lease_token=NULL,lease_expires_at=NULL,summary=?::jsonb,runs=?::jsonb WHERE query_id=? AND lease_token=? AND state='preparing' AND lease_expires_at>clock_timestamp() AND expires_at>clock_timestamp()",encodedSummary,encodedRuns,query.requestId(),lease);
                if(changed!=1)throw new InputRejected(409,"mtmc_query_cancelled");
                db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'mtmc_query','candidate_snapshot_created',?)",actor,query.requestId());
            });return page(query.requestId(),actor,revision,0,limit,null);
        }finally{db.update("DELETE FROM mtmc_queries WHERE query_id=? AND state='preparing' AND lease_token=?",query.requestId(),lease);}
    }
    private void verifyAnchors(Map<String,Object> summary){
        for(var source:objects(summary.get("_anchor_sources"))){sources.verify(source);observations.anchor(UUID.fromString((String)source.get("observation_id")),((Number)source.get("record_version")).longValue());}
        for(var stored:objects(summary.get("_anchor_points"))){
            var current=observations.anchor(UUID.fromString((String)stored.get("observation_id")),((Number)stored.get("record_version")).longValue());
            if(!CanonicalJson.encode(current.view(),65536).sha256().equals(CanonicalJson.encode(stored,65536).sha256()))throw new InputRejected(409,"mtmc_source_changed");
        }
        for(var stored:objects(summary.get("_features"))){
            var profile=new AppearanceCatalog.Profile((String)stored.get("profile"),(String)stored.get("profile_sha256"),((Number)stored.get("dimensions")).intValue(),object(stored.get("definition")));
            var feature=features.current(profile,object(stored.get("source"))).orElseThrow(()->new InputRejected(409,"mtmc_source_changed"));
            if(!feature.vectorSha256().equals(stored.get("vector_sha256"))||!feature.contentSha256().equals(stored.get("content_sha256")))throw new InputRejected(409,"mtmc_source_changed");
        }
        for(var stored:objects(summary.get("_anchor_plates")))if(!plates.resolve(UUID.fromString((String)stored.get("observation_id"))).sha256().equals(stored.get("sha256")))throw new InputRejected(409,"mtmc_source_changed");
    }
    private boolean current(Map<String,Object> item){
        try{
            for(var sight:objects(item.get("sightings"))){var source=object(sight.get("source"));sources.verify(source);var point=observations.anchor(UUID.fromString((String)source.get("observation_id")),((Number)source.get("record_version")).longValue());
                if(!permissions.mayReadCamera(point.cameraId())||!CanonicalJson.encode(point.view(),65536).sha256().equals(CanonicalJson.encode(object(sight.get("observation")),65536).sha256()))return false;var plate=object(sight.get("plate"));
                if(plate.get("sha256") instanceof String sha&&!plates.resolve(point.observationId()).sha256().equals(sha))return false;
            }return true;
        }catch(InputRejected unavailable){if(unavailable.status!=404&&unavailable.status!=409)throw unavailable;return false;}
        catch(cn.trial.videosearch.admin.AdminService.Rejected unavailable){if(unavailable.status!=404&&unavailable.status!=409)throw unavailable;return false;}
    }
    public Map<String,Object> page(UUID id,String actor,long revision,int offset,int limit,String runId){
        requireEnabled();if(offset<0||offset>1000||limit<1||limit>MAX_PAGE||runId!=null&&!runId.matches("[A-Za-z0-9_-]{1,80}:[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException();
        var rows=stored(id);if(rows.isEmpty())throw new InputRejected(404,"mtmc_query_unavailable");var row=rows.getFirst();String scope=scope();check(row,actor,revision,null,scope);
        if(!"completed".equals(row.get("state")))throw new InputRejected(409,"mtmc_query_running");
        var summary=object(JSON.readValue((String)row.get("summary_text"),Object.class));verifyAnchors(summary);var runs=objects(JSON.readValue((String)row.get("runs_text"),Object.class));
        String selection=runId==null?(String)summary.get("selected_run"):runId;var selected=runs.stream().filter(run->selection.equals(run.get("id"))).findFirst().orElseThrow(IllegalArgumentException::new);var all=objects(selected.get("items"));
        if(offset>all.size())throw new IllegalArgumentException();var items=new ArrayList<Map<String,Object>>();int next=offset,removed=0;long deadline=System.nanoTime()+60_000_000_000L;
        while(next<all.size()&&items.size()<limit){budget(deadline);var item=all.get(next++);if(current(item))items.add(item);else removed++;}
        verifyAnchors(summary);var again=stored(id);if(again.isEmpty())throw new InputRejected(410,"mtmc_query_expired");check(again.getFirst(),actor,revision,null,scope());
        var safeSummary=new LinkedHashMap<>(summary);safeSummary.keySet().removeIf(key->key.startsWith("_"));
        var response=new LinkedHashMap<String,Object>();response.put("schema_version","mtmc-results-v1");response.put("query_id",id.toString());response.put("anchor",summary.get("anchor"));response.put("summary",safeSummary);
        response.put("runs",runs.stream().map(run->{var metadata=new LinkedHashMap<>(run);metadata.remove("items");return metadata;}).toList());response.put("selected_run",selection);response.put("items",items);response.put("total_candidate_periods",all.size());response.put("removed_stale_periods",removed);response.put("has_more",next<all.size());response.put("next_offset",next<all.size()?next:null);response.put("offset",offset);response.put("expires_at",((java.sql.Timestamp)row.get("expires_at")).toInstant().toString());return response;
    }
    public Map<String,Object> close(UUID id,String actor,long revision){requireEnabled();db.update("DELETE FROM mtmc_queries WHERE query_id=? AND actor_id=? AND actor_revision=?",id,actor,revision);return Map.of("closed",true);}
}
