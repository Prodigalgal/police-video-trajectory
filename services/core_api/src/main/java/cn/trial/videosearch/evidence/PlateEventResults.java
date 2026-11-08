package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Event-level scheduling history and explicit review; never assigns cross-camera identity. */
@Service
public class PlateEventResults {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate db;
    private final int eventLimit;
    public PlateEventResults(JdbcTemplate db,@Value("${VIDEO_SPECIALIST_EVENT_ATTEMPTS:3}")int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid event attempt limit");this.db=db;eventLimit=limit;
    }
    public record Review(UUID requestId,String resultSha256,int regionIndex,String decision,long expectedRevision,boolean originalChecked){}
    public Map<String,Object> assess(Map<String,Object> summary,Map<String,Object> descriptor){
        if(!(summary.get("event_id") instanceof String event))return summary;
        var output=new LinkedHashMap<>(summary);
        var reusable=db.queryForList("""
            SELECT r.job_id,r.frame_id,r.revision,j.input_id,j.input_manifest_sha256,j.profile
            FROM specialist_plate_reviews r JOIN specialist_analysis_jobs j ON j.job_id=r.job_id
            JOIN specialist_analysis_results s ON s.job_id=j.job_id AND s.result_sha256=r.result_sha256
            JOIN analysis_inputs i ON i.input_id=j.input_id JOIN observations o ON o.observation_id=i.anchor_observation_id
            JOIN capture_outbox c ON c.frame_id=j.frame_id JOIN frames f ON f.frame_id=j.frame_id
            WHERE r.event_id=? AND r.camera_id=? AND r.review_state='confirmed' AND j.state='completed'
              AND NOT o.deleted AND o.record_version=i.anchor_record_version AND f.analysis_state='completed'
              AND j.result_sha256=r.result_sha256 AND c.scene_run_id=? AND c.media_epoch=?
              AND c.payload_sha256=j.input_descriptor->>'evidence_sha256' AND s.result->>'evidence_sha256'=c.payload_sha256
              AND c.relative_media_ms<=? AND c.relative_media_ms>=?-10000
            ORDER BY r.updated_at DESC LIMIT 1
            """,UUID.fromString(event),descriptor.get("camera_id"),UUID.fromString((String)summary.get("scene_run_id")),summary.get("media_epoch"),summary.get("relative_media_ms"),summary.get("relative_media_ms"));
        if(!reusable.isEmpty()&&!"verify_vehicle".equals(summary.get("intent"))){
            var saved=reusable.getFirst();var reference=new LinkedHashMap<String,Object>();
            for(String key:List.of("job_id","frame_id","input_id","input_manifest_sha256","profile","revision"))reference.put(key,saved.get(key) instanceof UUID id?id.toString():saved.get(key));
            output.put("reuse_reference",reference);return state(output,"reused","reuse_result","confirmed_event_result_available");
        }
        var stats=db.queryForList("""
            SELECT count(*) AS admissions,count(*) FILTER(WHERE j.state IN ('queued','active')) AS pending,
              count(*) FILTER(WHERE j.state='completed' AND r.result->>'outcome'='recognized') AS recognized
            FROM specialist_analysis_admissions a JOIN specialist_analysis_jobs j ON j.job_id=a.job_id
            LEFT JOIN specialist_analysis_results r ON r.job_id=j.job_id
            WHERE a.decision->>'event_id'=? AND a.camera_id=?
            """,event,descriptor.get("camera_id")).getFirst();
        if(((Number)stats.get("pending")).longValue()>0&&!"verify_vehicle".equals(summary.get("intent")))return state(output,"deferred","waiting_result","event_job_pending");
        long admissions=((Number)stats.get("admissions")).longValue();
        if(((Number)stats.get("recognized")).longValue()>0&&(!"selected".equals(summary.get("decision"))||admissions>=eventLimit))return state(output,"deferred","pending_review","event_result_awaiting_review");
        if(admissions>=eventLimit)return state(output,"skipped","unreadable","event_attempts_exhausted");
        return output;
    }
    private static Map<String,Object> state(Map<String,Object> value,String decision,String state,String reason){
        value.put("decision",decision);value.put("business_state",state);value.put("reasons",List.of(reason));return value;
    }
    public Map<String,Object> reviewState(UUID job){
        var rows=db.queryForList("SELECT review_state,revision,region_index,result_sha256 FROM specialist_plate_reviews WHERE job_id=?",job);
        return rows.isEmpty()?Map.of("state","unreviewed","revision",0):Map.of("state",rows.getFirst().get("review_state"),"revision",rows.getFirst().get("revision"),"region_index",rows.getFirst().get("region_index"));
    }
    private Optional<PlateReviewTarget> target(UUID job,Map<String,Object> row,Map<String,Object> result){
        var admissions=db.queryForList("SELECT frame_id,camera_id,decision::text AS decision FROM specialist_analysis_admissions WHERE job_id=? ORDER BY created_at,request_id LIMIT 1",job);
        if(admissions.isEmpty())return Optional.empty();var admission=admissions.getFirst();
        if(!Objects.equals(row.get("frame_id"),admission.get("frame_id"))||!Objects.equals(row.get("camera_id"),admission.get("camera_id")))throw new InputRejected(409,"plate_review_unavailable");
        var decision=SpecialistResult.object(JSON.readValue((String)admission.get("decision"),Object.class));
        if(!decision.containsKey("event_id"))return Optional.empty();return Optional.of(PlateReviewTarget.bind(decision,row,result));
    }
    public Optional<Map<String,Object>> reviewTarget(UUID job,Map<String,Object> row,Map<String,Object> result){return target(job,row,result).map(value->value.view(row,result));}
    public Map<String,Object> review(UUID job,Map<String,Object> row,Map<String,Object> result,Review body,String actor){
        if(body==null||body.requestId()==null||body.expectedRevision()<0||body.regionIndex()<0||body.decision()==null||!Set.of("confirm","revoke").contains(body.decision())
            ||!Objects.equals(body.resultSha256(),row.get("result_sha256"))||!"license_plate".equals(row.get("operation")))throw new InputRejected(422,"invalid_plate_review");
        var fingerprint=CanonicalJson.encode(Map.of("job",job.toString(),"result_sha256",body.resultSha256(),"region",body.regionIndex(),"decision",body.decision(),"revision",body.expectedRevision(),"checked",body.originalChecked()),8192).sha256();
        var old=db.queryForList("SELECT actor_id,request_sha256,response::text AS response FROM specialist_plate_review_requests WHERE request_id=?",body.requestId());
        if(!old.isEmpty()){
            if(!actor.equals(old.getFirst().get("actor_id"))||!fingerprint.equals(old.getFirst().get("request_sha256")))throw new InputRejected(409,"plate_review_conflict");
            return SpecialistResult.object(JSON.readValue((String)old.getFirst().get("response"),Object.class));
        }
        if(!"recognized".equals(result.get("outcome"))||!(result.get("regions") instanceof List<?> regions)||body.regionIndex()>=regions.size())throw new InputRejected(422,"invalid_plate_review");
        var target=target(job,row,result).orElseThrow(()->new InputRejected(409,"plate_review_unavailable"));
        if(!target.eligible().contains(body.regionIndex()))throw new InputRejected(422,"plate_review_target_mismatch");
        if("confirm".equals(body.decision())&&(!body.originalChecked()||!(result.get("limitations") instanceof List<?> limitations)||limitations.stream().anyMatch(v->Set.of("uncertain_characters","partial_text","inconsistent_readings").contains(v))))throw new InputRejected(422,"invalid_plate_review");
        var previous=db.queryForList("SELECT revision FROM specialist_plate_reviews WHERE job_id=? FOR UPDATE",job);
        long revision=previous.isEmpty()?0:((Number)previous.getFirst().get("revision")).longValue();
        if(revision!=body.expectedRevision())throw new InputRejected(409,"plate_review_conflict");
        String state="confirm".equals(body.decision())?"confirmed":"revoked";
        db.update("""
            INSERT INTO specialist_plate_reviews(job_id,event_id,frame_id,camera_id,result_sha256,region_index,review_state,revision,actor_id)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET result_sha256=EXCLUDED.result_sha256,region_index=EXCLUDED.region_index,
            review_state=EXCLUDED.review_state,revision=EXCLUDED.revision,actor_id=EXCLUDED.actor_id,updated_at=clock_timestamp()
            """,job,target.eventId(),row.get("frame_id"),row.get("camera_id"),body.resultSha256(),body.regionIndex(),state,revision+1,actor);
        var response=Map.<String,Object>of("state",state,"revision",revision+1,"region_index",body.regionIndex(),"job_id",job.toString());
        db.update("INSERT INTO specialist_plate_review_requests(request_id,job_id,actor_id,request_sha256,response) VALUES(?,?,?,?,?::jsonb)",body.requestId(),job,actor,fingerprint,CanonicalJson.encode(response,8192).payload());
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'review_plate_result',?,?)",actor,state,body.requestId());
        return response;
    }
    static double iou(List<?> a,List<?> b){
        return PlateReviewTarget.iou(a,b);
    }
}
