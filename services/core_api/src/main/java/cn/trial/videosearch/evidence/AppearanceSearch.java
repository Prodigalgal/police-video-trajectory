package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Frozen ranked query snapshots; uploads and query vectors are never persisted. */
@Service
public class AppearanceSearch {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate db;private final AppearanceJobs jobs;private final AppearanceCatalog catalog;private final AppearanceSource sources;
    private final AppearanceIndex index;private final SearchOrchestrator permissions;private final AppearanceFusion fusion;private final TransactionTemplate tx;
    public AppearanceSearch(JdbcTemplate db,AppearanceJobs jobs,AppearanceCatalog catalog,AppearanceSource sources,AppearanceIndex index,SearchOrchestrator permissions,AppearanceFusion fusion,PlatformTransactionManager manager){this.db=db;this.jobs=jobs;this.catalog=catalog;this.sources=sources;this.index=index;this.permissions=permissions;this.fusion=fusion;tx=new TransactionTemplate(manager);}
    public record Query(UUID requestId,String profile,String profileSha256,String imageSha256,List<Double> bbox,int imageWidth,int imageHeight,String targetType,List<String> cameras,String start,String end,List<Double> vector,AppearanceFusion.Semantic semantic){}
    public Map<String,Object> create(Query query,String actor,long revision){
        jobs.requireEnabled();if(query==null||query.requestId()==null||query.profile()==null||query.targetType()==null||!AppearanceCatalog.hex(query.imageSha256())||!Set.of("person","vehicle").contains(query.targetType()))throw new IllegalArgumentException();
        AppearanceSource.validateBox(query.bbox(),query.imageWidth(),query.imageHeight());var snapshot=catalog.current();var profile=snapshot.profiles().get(query.profile());
        if(profile==null||!profile.sha256().equals(query.profileSha256()))throw new InputRejected(409,"appearance_profile_conflict");
        AppearanceVectors.validate(query.vector(),profile.dimensions());
        var cameras=query.cameras()==null||query.cameras().isEmpty()?new ArrayList<>(permissions.readableCameras()):query.cameras();
        if(cameras.isEmpty()||cameras.size()>50||new HashSet<>(cameras).size()!=cameras.size()||cameras.stream().anyMatch(camera->!permissions.mayReadCamera(camera)))throw new InputRejected(403,"appearance_scope_forbidden");
        cameras=cameras.stream().sorted().toList();Instant start=instant(query.start()),end=instant(query.end());
        if(start!=null&&end!=null&&start.isAfter(end))throw new IllegalArgumentException();
        var semantic=AppearanceFusion.validated(query.semantic());
        var request=new LinkedHashMap<String,Object>();request.put("profile_sha256",profile.sha256());request.put("image_sha256",query.imageSha256());request.put("bbox_xyxy",query.bbox());request.put("image_width",query.imageWidth());request.put("image_height",query.imageHeight());request.put("target_type",query.targetType());request.put("cameras",cameras);request.put("start",query.start());request.put("end",query.end());request.put("vector_sha256",CanonicalJson.encode(query.vector(),32768).sha256());
        request.put("semantic",semantic);
        String fingerprint=CanonicalJson.encode(request,16384).sha256(),scope=scope();
        var old=stored(query.requestId());if(!old.isEmpty()){check(old.getFirst(),actor,revision,fingerprint,scope);return page(query.requestId(),actor,revision,0,10);}
        var filter=new AppearanceIndex.Filter(query.targetType(),cameras,start,end);
        var found=index.find(profile,query.vector(),filter,1000);var combined=fusion.apply(semantic,found.candidates(),filter);
        var groups=group(found.candidates(),combined.rankings());var summary=new LinkedHashMap<String,Object>();summary.put("semantic",combined.summary());summary.put("profile",profile.id());summary.put("profile_sha256",profile.sha256());summary.put("image_sha256",query.imageSha256());summary.put("target_type",query.targetType());summary.put("scanned",found.scanned());summary.put("candidate_limit",1000);summary.put("truncated",found.truncated());summary.put("cameras",cameras);summary.put("time_filter_verified_only",start!=null||end!=null);
        String encoded=CanonicalJson.encode(groups,4*1024*1024).payload();
        tx.executeWithoutResult(status->{db.execute("SELECT pg_advisory_xact_lock(918273652)");var replay=stored(query.requestId());
            if(!replay.isEmpty()){check(replay.getFirst(),actor,revision,fingerprint,scope);return;}
            db.update("DELETE FROM appearance_queries WHERE query_id IN (SELECT query_id FROM appearance_queries WHERE expires_at<clock_timestamp() ORDER BY expires_at LIMIT 200)");
            if(db.queryForObject("SELECT count(*) FROM appearance_queries WHERE actor_id=? AND expires_at>clock_timestamp()",Long.class,actor)>=20||db.queryForObject("SELECT count(*) FROM appearance_queries WHERE expires_at>clock_timestamp()",Long.class)>=200)throw new InputRejected(429,"appearance_query_limit");
            db.update("INSERT INTO appearance_queries(query_id,actor_id,actor_revision,request_sha256,profile_sha256,scope_sha256,summary,candidates) VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb)",query.requestId(),actor,revision,fingerprint,profile.sha256(),scope,CanonicalJson.encode(summary,16384).payload(),encoded);
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'appearance_search','completed',?)",actor,query.requestId());
        });return page(query.requestId(),actor,revision,0,10);
    }
    private String scope(){return CanonicalJson.encode(permissions.readableCameras().stream().sorted().toList(),8192).sha256();}
    private static Instant instant(String value){try{return value==null?null:Instant.parse(value);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Invalid appearance time");}}
    private List<Map<String,Object>> stored(UUID id){return db.queryForList("SELECT *,summary::text AS summary_text,candidates::text AS candidates_text,expires_at>clock_timestamp() AS alive FROM appearance_queries WHERE query_id=?",id);}
    private static void check(Map<String,Object> row,String actor,long revision,String fingerprint,String scope){
        if(!actor.equals(row.get("actor_id"))||revision!=((Number)row.get("actor_revision")).longValue())throw new InputRejected(404,"appearance_query_unavailable");
        if(!Boolean.TRUE.equals(row.get("alive")))throw new InputRejected(410,"appearance_query_expired");
        if(!scope.equals(row.get("scope_sha256")))throw new InputRejected(409,"appearance_scope_changed");
        if(fingerprint!=null&&!fingerprint.equals(row.get("request_sha256")))throw new InputRejected(409,"appearance_request_conflict");
    }
    public Map<String,Object> page(UUID id,String actor,long revision,int offset,int limit){
        jobs.requireEnabled();if(offset<0||offset>1000||limit<1||limit>20)throw new IllegalArgumentException();var rows=stored(id);if(rows.isEmpty())throw new InputRejected(404,"appearance_query_unavailable");var row=rows.getFirst();check(row,actor,revision,null,scope());
        var all=(List<?>)JSON.readValue((String)row.get("candidates_text"),Object.class);if(offset>all.size())throw new IllegalArgumentException();
        var items=new ArrayList<Map<String,Object>>();int next=offset,removed=0;
        while(next<all.size()&&items.size()<limit){var original=AppearanceCatalog.object(all.get(next++));var sightings=(List<?>)original.get("sightings");var current=new ArrayList<Map<String,Object>>();
            for(Object raw:sightings){var sight=AppearanceCatalog.object(raw);var source=AppearanceCatalog.object(sight.get("source"));
                try{sources.verify(source);current.add(sight);}catch(InputRejected unavailable){if(unavailable.status!=404&&unavailable.status!=409)throw unavailable;removed++;}}
            if(current.isEmpty())continue;
            items.add(external(current));
        }
        // Recheck snapshot expiry/scope after validating candidate sources.
        check(stored(id).getFirst(),actor,revision,null,scope());
        var response=new LinkedHashMap<String,Object>();response.put("schema_version","appearance-results-v1");response.put("query_id",id.toString());response.put("summary",JSON.readValue((String)row.get("summary_text"),Object.class));response.put("items",items);response.put("total_candidate_periods",all.size());response.put("removed_stale_sightings",removed);response.put("has_more",next<all.size());response.put("next_offset",next<all.size()?next:null);response.put("offset",offset);response.put("expires_at",((java.sql.Timestamp)row.get("expires_at")).toInstant().toString());return response;
    }
    public Map<String,Object> close(UUID id,String actor,long revision){jobs.requireEnabled();db.update("DELETE FROM appearance_queries WHERE query_id=? AND actor_id=? AND actor_revision=?",id,actor,revision);return Map.of("closed",true);}
    static List<Map<String,Object>> group(List<AppearanceIndex.Ranked> hits){
        return group(hits,Map.of());
    }
    static List<Map<String,Object>> group(List<AppearanceIndex.Ranked> hits,Map<String,Map<String,Object>> rankings){
        var tracks=new LinkedHashMap<String,List<AppearanceIndex.Ranked>>();
        for(var hit:hits){var c=hit.clock();boolean clock=c.get("scene_run_id")!=null&&c.get("media_epoch")!=null&&c.get("relative_media_ms") instanceof Number&&!hit.track().isBlank();
            String key=clock?hit.source().get("camera_id")+":"+c.get("task_id")+":"+c.get("scene_run_id")+":"+c.get("media_epoch")+":"+hit.track():"single:"+hit.source().get("observation_id");tracks.computeIfAbsent(key,ignored->new ArrayList<>()).add(hit);}
        var groups=new ArrayList<Map<String,Object>>();
        for(var track:tracks.values()){
            track.sort(Comparator.comparingLong(hit->millis(hit.clock())));var segment=new ArrayList<Map<String,Object>>();long previous=-1,first=-1;
            for(var hit:track){long position=millis(hit.clock());if(!segment.isEmpty()&&(position-previous>5500||position-first>300000||segment.size()>=50)){groups.add(Map.of("sightings",List.copyOf(segment)));segment.clear();}
                if(segment.isEmpty())first=position;
                var sight=new LinkedHashMap<String,Object>();sight.put("source",hit.source());sight.put("clock",hit.clock());sight.put("score",hit.score());
                var ranking=rankings.get((String)hit.source().get("observation_id"));if(ranking!=null){sight.put("rank_score",ranking.get("score"));sight.put("ranking",ranking);}segment.add(sight);previous=position;}
            if(!segment.isEmpty())groups.add(Map.of("sightings",List.copyOf(segment)));
        }
        groups.sort(Comparator.<Map<String,Object>>comparingDouble(group->-best(group)).thenComparing(group->firstId(group)));return List.copyOf(groups);
    }
    private static long millis(Map<String,Object> clock){return clock.get("relative_media_ms") instanceof Number value?value.longValue():0;}
    private static double rankScore(Map<String,Object> sight){return ((Number)sight.getOrDefault("rank_score",sight.get("score"))).doubleValue();}
    private static double best(Map<String,Object> group){return ((List<?>)group.get("sightings")).stream().map(AppearanceCatalog::object).mapToDouble(AppearanceSearch::rankScore).max().orElse(-1);}
    private static String firstId(Map<String,Object> group){return (String)AppearanceCatalog.object(AppearanceCatalog.object(((List<?>)group.get("sightings")).getFirst()).get("source")).get("observation_id");}
    static Map<String,Object> external(List<Map<String,Object>> sightings){
        var sorted=new ArrayList<>(sightings);sorted.sort(Comparator.comparingDouble(sight->-rankScore(sight)));
        var chosen=sorted.getFirst();var source=AppearanceCatalog.object(chosen.get("source"));var clock=AppearanceCatalog.object(chosen.get("clock"));
        var value=new LinkedHashMap<>(source);value.remove("frame_fingerprint");value.remove("anchor_fingerprint");value.put("similarity",chosen.get("score"));value.put("sightings",sightings.size());
        if(chosen.containsKey("ranking"))value.put("ranking",chosen.get("ranking"));
        value.put("source_time",clock.get("source_time"));value.put("source_time_origin",clock.get("source_time_origin"));value.put("received_at",clock.get("received_at"));value.put("media_offset_ms",clock.get("media_offset_ms"));
        var first=AppearanceCatalog.object(sightings.getFirst().get("clock"));var last=AppearanceCatalog.object(sightings.getLast().get("clock"));
        boolean known=first.get("relative_media_ms") instanceof Number&&last.get("relative_media_ms") instanceof Number;
        var period=new LinkedHashMap<String,Object>();period.put("clock",first.get("source_time")!=null&&last.get("source_time")!=null?"verified_source":known?"relative_media":"unknown");
        period.put("start_ms",known?millis(first):null);period.put("end_ms",known?millis(last):null);period.put("association","local_geometry_candidate");value.put("period",period);
        value.put("period_start",first.get("source_time"));value.put("period_end",last.get("source_time"));value.put("sample_observation_ids",sorted.stream().limit(3).map(sight->AppearanceCatalog.object(sight.get("source")).get("observation_id")).toList());
        return value;
    }
}
