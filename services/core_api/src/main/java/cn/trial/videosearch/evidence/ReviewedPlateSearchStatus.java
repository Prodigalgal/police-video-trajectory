package cn.trial.videosearch.evidence;

import cn.trial.videosearch.http.SecureJsonClient;
import cn.trial.videosearch.index.ObservationIndexReader;
import cn.trial.videosearch.index.ReviewedPlateDocument;
import cn.trial.videosearch.ingest.InferenceReceipts;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** A point-in-time readiness check, not a repair or an index publication receipt. */
@Service
public class ReviewedPlateSearchStatus {
    private static final Logger LOG=LoggerFactory.getLogger(ReviewedPlateSearchStatus.class);
    private static final int MAX_DOCUMENT_BYTES=128*1024,MAX_ATTEMPTS=10;
    private final JdbcTemplate db;private final ReviewedPlateFacts facts;private final ObservationIndexReader index;private final SearchOrchestrator scope;
    public ReviewedPlateSearchStatus(JdbcTemplate db,ReviewedPlateFacts facts,ObservationIndexReader index,SearchOrchestrator scope){this.db=db;this.facts=facts;this.index=index;this.scope=scope;}
    private Map<String,Object> projection(UUID observation,long version){
        var rows=db.queryForList("""
            SELECT t.requested_revision,t.published_revision,t.source_record_version,t.evidence_sha256,t.fact_sha256,
             t.projection_outcome,t.attempts,t.last_error_code,t.next_attempt_at,i.document::text AS document
            FROM reviewed_plate_index_tasks t LEFT JOIN index_outbox i ON i.observation_id=t.observation_id AND i.record_version=?
            WHERE t.observation_id=?
            """,version,observation);
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    public Map<String,Object> read(UUID observation,long version){
        if(observation==null||version<1||version>9_007_199_254_740_991L)throw new InputRejected(422,"invalid_plate_search_request");
        var before=facts.resolve(observation);
        if(before.frame()==null||!scope.mayReadCamera(before.camera()))throw new InputRejected(404,"observation_unavailable");
        if(before.version()!=version)throw new InputRejected(409,"observation_revision_conflict");
        var task=projection(observation,version);
        String state=state(before,task);
        if(!before.sha256().equals(facts.resolve(observation).sha256())||!task.equals(projection(observation,version)))throw new InputRejected(409,"plate_search_changed");
        var result=new LinkedHashMap<String,Object>();
        result.put("schema_version","reviewed-plate-search-status-v1");result.put("observation_id",observation.toString());result.put("record_version",version);
        result.put("frame_id",before.frame().toString());result.put("camera_id",before.camera());result.put("state",state);result.put("checked_at",Instant.now().toString());
        result.put("number",before.number());result.put("proofs",before.proofs());result.put("fact_sha256",before.sha256());
        result.put("requested_revision",task.get("requested_revision"));result.put("published_revision",task.get("published_revision"));
        result.put("query","searchable".equals(state)?"车牌 "+before.number():null);
        return result;
    }
    private String state(ReviewedPlateFacts.Snapshot snapshot,Map<String,Object> task){
        if(!facts.enabled())return "disabled";
        if(!"ready".equals(snapshot.outcome()))return switch(snapshot.outcome()){
            case "empty"->"review_required";case "conflict"->"review_conflict";default->"source_changed";
        };
        if(task.isEmpty())return "not_queued";
        long requested=number(task,"requested_revision"),published=number(task,"published_revision");
        if(requested>published)return number(task,"attempts")>=MAX_ATTEMPTS?"sync_failed":task.get("last_error_code")!=null?"retry_wait":"pending";
        if(requested<1||requested!=published||number(task,"source_record_version")!=snapshot.version()||!"ready".equals(task.get("projection_outcome"))
                ||!snapshot.evidence().equals(task.get("evidence_sha256"))||!snapshot.sha256().equals(task.get("fact_sha256")))return "stale";
        try{
            if(!(task.get("document") instanceof String encoded))return "stale";
            var expected=ReviewedPlateDocument.build(snapshot,published,InferenceReceipts.parse(encoded));
            var response=index.readReviewedPlates(List.of(snapshot.observation()));
            if(!(response.get("docs") instanceof List<?> docs)||docs.size()!=1||!(docs.getFirst() instanceof Map<?,?> actual)
                    ||!snapshot.observation().toString().equals(actual.get("_id"))||actual.containsKey("error"))return "unavailable";
            if(Boolean.FALSE.equals(actual.get("found")))return "not_indexed";
            if(!Boolean.TRUE.equals(actual.get("found"))||!(actual.get("_version") instanceof Number revision)||!(actual.get("_source") instanceof Map<?,?> source))return "unavailable";
            if(revision.doubleValue()!=revision.longValue()||revision.longValue()!=published)return "stale";
            return CanonicalJson.encode(expected,MAX_DOCUMENT_BYTES).sha256().equals(CanonicalJson.encode(source,MAX_DOCUMENT_BYTES).sha256())?"searchable":"stale";
        }catch(ReviewedPlateDocument.SourceChanged failure){return "stale";}
        catch(SecureJsonClient.UpstreamFailure|IllegalArgumentException failure){
            LOG.warn("Reviewed plate projection verification unavailable for observation {}",snapshot.observation(),failure);return "unavailable";
        }
    }
    private static long number(Map<String,Object> row,String key){return row.get(key) instanceof Number value?value.longValue():-1;}
}
