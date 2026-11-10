package cn.trial.videosearch.evidence;

import cn.trial.videosearch.ingest.InferenceReceipts;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Read a bounded lineage, then verify files outside database transactions. */
@Service
public class ProcessingTrace {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProcessingTrace.class);
    private final JdbcTemplate db;private final SearchOrchestrator scope;private final EvidenceFiles files;private final ProcessingTraceResources resources;
    public ProcessingTrace(JdbcTemplate db,SearchOrchestrator scope,EvidenceFiles files,ProcessingTraceResources resources){this.db=db;this.scope=scope;this.files=files;this.resources=resources;}
    public Map<String,Object> observation(UUID observation,long version){return observation(observation,version,false);}
    public Map<String,Object> observation(UUID observation,long version,boolean verifyAssets){
        var rows=db.queryForList("SELECT frame_id,camera_id,record_version FROM observations WHERE observation_id=? AND NOT deleted",observation);
        if(rows.size()!=1||!scope.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"trace_unavailable");
        if(((Number)rows.getFirst().get("record_version")).longValue()!=version)throw new InputRejected(409,"trace_source_changed");
        var result=frame((UUID)rows.getFirst().get("frame_id"),verifyAssets);
        var current=db.queryForList("SELECT record_version,deleted FROM observations WHERE observation_id=?",observation);
        if(current.size()!=1||Boolean.TRUE.equals(current.getFirst().get("deleted"))||((Number)current.getFirst().get("record_version")).longValue()!=version)throw new InputRejected(409,"trace_source_changed");
        result.put("observation_id",observation.toString());result.put("record_version",version);return result;
    }
    private Map<String,Object> source(UUID frame){
        var rows=db.queryForList("""
            WITH anchor AS (SELECT frame_id FROM frames WHERE frame_id=? UNION SELECT frame_id FROM capture_outbox WHERE frame_id=?)
            SELECT a.frame_id,coalesce(f.evidence_ref,c.evidence_ref) AS evidence_ref,s.camera_id,cam.display_name,s.source_type,
             f.analysis_state,f.received_at,c.payload_sha256,c.plan::text AS plan,c.registered_at,c.enqueued_at,
             c.scene_run_id,c.media_epoch,c.relative_media_ms,c.source_time_origin,c.source_time,
             j.state AS job_state,j.attempts,j.last_error_code,j.updated_at AS job_updated
            FROM anchor a LEFT JOIN frames f ON f.frame_id=a.frame_id LEFT JOIN capture_outbox c ON c.frame_id=a.frame_id
             JOIN processing_tasks t ON t.task_id=coalesce(f.task_id,c.task_id) JOIN sources s ON s.source_id=t.source_id
             JOIN cameras cam ON cam.camera_id=s.camera_id LEFT JOIN analysis_jobs j ON j.frame_id=a.frame_id
            WHERE c.frame_id IS NULL OR c.camera_id=s.camera_id
            """,frame,frame);
        if(rows.size()!=1||!scope.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"trace_unavailable");
        return rows.getFirst();
    }
    public EvidenceFiles.FrameFile image(UUID frame,String expected)throws java.io.IOException{
        var row=source(frame);if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
        var image=files.read(frame,(String)row.get("evidence_ref"));
        if(image==null||!expected.equals(image.sha256())||row.get("payload_sha256")!=null&&!expected.equals(row.get("payload_sha256")))throw new InputRejected(409,"trace_source_changed");
        var current=source(frame);for(String key:List.of("camera_id","evidence_ref","payload_sha256"))if(!Objects.equals(row.get(key),current.get(key)))throw new InputRejected(409,"trace_source_changed");
        return image;
    }
    public Map<String,Object> frame(UUID frame){return frame(frame,false);}
    public Map<String,Object> frame(UUID frame,boolean verifyAssets){
        var row=source(frame);var checks=new ArrayList<Map<String,Object>>();var stages=new ArrayList<Map<String,Object>>();
        Map<String,Object> plan=row.get("plan")==null?Map.of():InferenceReceipts.parse((String)row.get("plan"));
        var stored=receipt(frame);var sealed=stored.value();String expected=(String)row.get("payload_sha256");
        if(expected==null&&sealed!=null&&sealed.get("image_sha256") instanceof String digest)expected=digest;
        EvidenceFiles.FrameFile image;boolean readFailed=false;
        try{image=files.read(frame,(String)row.get("evidence_ref"));}catch(java.io.IOException unreadable){image=null;readFailed=true;LOG.warn("Trace evidence read unavailable for frame {}",frame,unreadable);}
        boolean matched=image!=null&&expected!=null&&expected.equals(image.sha256());
        checks.add(check("original_image",readFailed?"unavailable":image==null?"missing":expected==null?"unverified":matched?"matched":"changed"));
        boolean receiptValid=false;Map<String,Object> model=Map.of();
        if(sealed!=null&&stored.consistent()){
            try{
                InferenceReceipts.validate(sealed,expected,(String)sealed.get("configured_model"),(String)sealed.get("prompt_version"));receiptValid=true;
                var declared=new LinkedHashMap<String,Object>();
                for(String key:List.of("configured_model","model_binding","gateway_version","source_revision","prompt_version","protocol","latency_ms","streaming","timeout_budget_ms"))declared.put(key,sealed.get(key));
                model=declared;
            }catch(IllegalArgumentException invalid){receiptValid=false;}
        }
        checks.add(check("inference_receipt",!stored.present()?"missing":receiptValid?"matched":"changed"));
        stages.add(stage("capture",row.get("registered_at")==null?"missing":"recorded",time(row.get("registered_at")),Map.of()));
        stages.add(stage("preprocess",plan.isEmpty()?"missing":"recorded",null,details(plan,"detection_state","selection_policy","selection_metrics","activity_context")));
        int targetCount=plan.get("targets") instanceof List<?> targets?targets.size():0;
        stages.add(stage("target_detection",plan.get("targets") instanceof List<?>?"recorded":"missing",null,Map.of("target_count",targetCount)));
        var semanticDetails=new LinkedHashMap<>(model);semanticDetails.put("attempts",row.get("attempts"));semanticDetails.put("last_error_code",row.get("last_error_code"));
        boolean localSkip=plan.get("selection_metrics") instanceof Map<?,?> metrics&&metrics.get("activity_target_routing") instanceof Map<?,?> route&&"local_no_moving_person_vehicle".equals(route.get("route"))&&"completed".equals(row.get("job_state"));
        stages.add(stage("semantic_analysis",localSkip?"skipped":row.get("job_state")==null?"not_started":(String)row.get("job_state"),time(row.get("job_updated")),semanticDetails));
        var modules=db.queryForList("SELECT module_key,completed_at FROM analysis_modules WHERE frame_id=? ORDER BY module_key",frame);
        stages.add(stage("module_projection",modules.isEmpty()?"missing":modules.stream().allMatch(value->value.get("completed_at")!=null)?"completed":"pending",null,Map.of("module_count",modules.size())));
        var index=db.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE i.completed_at IS NOT NULL) AS submitted FROM index_outbox i JOIN observations o ON o.observation_id=i.observation_id AND o.record_version=i.record_version WHERE o.frame_id=?",frame);
        long total=((Number)index.get("total")).longValue(),submitted=((Number)index.get("submitted")).longValue();
        stages.add(stage("search_index",total==0?"not_started":total==submitted?"submitted":"pending",null,index));
        checks.add(check("elasticsearch_document","not_checked"));
        var recording=db.queryForList("SELECT l.segment_id,l.seek_offset_ms,l.payload_sha256,l.packet_sha256,s.manifest_sha256 FROM frame_recording_links l JOIN stream_recording_segments s USING(segment_id) WHERE l.frame_id=?",frame);
        var recordingDetails=new LinkedHashMap<String,Object>();
        if(!recording.isEmpty())recording.getFirst().forEach((key,value)->recordingDetails.put(key,value instanceof UUID id?id.toString():value));
        stages.add(stage("recording_binding",recording.isEmpty()?"missing":"recorded",null,recordingDetails));
        checks.add(check("recording_file","not_checked"));
        var reviews=db.queryForList("SELECT r.review_state,r.review_scope,r.revision FROM specialist_plate_reviews r JOIN specialist_analysis_jobs j ON j.job_id=r.job_id WHERE j.frame_id=? ORDER BY r.job_id LIMIT 32",frame);
        stages.add(stage("plate_review",reviews.isEmpty()?"not_started":"recorded",null,Map.of("reviews",reviews)));
        if(verifyAssets){
            var verified=new HashMap<String,Map<String,Object>>();
            for(var check:resources.verify(frame))verified.put((String)check.get("name"),check);
            checks.replaceAll(check->verified.getOrDefault(check.get("name"),check));
        }
        var now=source(frame);
        for(String key:List.of("payload_sha256","plan","camera_id","evidence_ref","scene_run_id","media_epoch","relative_media_ms","job_updated"))if(!Objects.equals(row.get(key),now.get(key)))throw new InputRejected(409,"trace_source_changed");
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schema_version","processing-replay-v1");manifest.put("frame_id",frame.toString());manifest.put("camera_id",row.get("camera_id"));manifest.put("image_sha256",expected);
        manifest.put("image_available",matched);manifest.put("request_available",matched&&receiptValid);manifest.put("preprocess",safe(plan,0));
        manifest.put("inference_receipt",receiptValid?sealed:null);
        var encoded=CanonicalJson.encode(manifest,512*1024);
        var result=new LinkedHashMap<String,Object>();result.put("schema_version","processing-trace-v1");result.put("frame_id",frame.toString());result.put("camera_id",row.get("camera_id"));result.put("camera_name",row.get("display_name"));result.put("checked_at",Instant.now().toString());
        result.put("checks",checks);result.put("stages",stages);result.put("replay_manifest_json",encoded.payload());result.put("replay_manifest_sha256",encoded.sha256());
        result.put("quality",Map.of("recognition_accuracy","not_evaluated","detector_scores_calibrated",false,"cross_frame_identity_confirmed",false));
        return result;
    }
    private record ReceiptRead(Map<String,Object> value,boolean present,boolean consistent){}
    private ReceiptRead receipt(UUID frame){
        var rows=db.queryForList("SELECT r.receipt::text AS metadata,r.receipt_sha256,q.content AS request_json,p.content AS response_json,s.content AS schema_json FROM frame_analysis_receipts r LEFT JOIN analysis_artifacts q ON q.artifact_sha256=r.request_sha256 LEFT JOIN analysis_artifacts p ON p.artifact_sha256=r.response_sha256 LEFT JOIN analysis_artifacts s ON s.artifact_sha256=r.schema_sha256 WHERE r.frame_id=?",frame);
        if(rows.isEmpty())return new ReceiptRead(null,false,false);var row=rows.getFirst();
        try{
            var value=new LinkedHashMap<>(InferenceReceipts.parse((String)row.get("metadata")));
            for(String key:List.of("request_json","response_json","schema_json"))value.put(key,row.get(key));
            return new ReceiptRead(value,true,Objects.equals(value.get("receipt_sha256"),row.get("receipt_sha256")));
        }catch(IllegalArgumentException malformed){LOG.warn("Trace receipt metadata is invalid for frame {}",frame);return new ReceiptRead(null,true,false);}
    }
    private static Map<String,Object> check(String name,String state){return Map.of("name",name,"state",state);}
    private static Map<String,Object> stage(String name,String state,String time,Map<String,Object> details){var value=new LinkedHashMap<String,Object>();value.put("name",name);value.put("state",state);value.put("at",time);value.put("details",safe(details,0));return value;}
    private static Map<String,Object> details(Map<String,Object> source,String...keys){var result=new LinkedHashMap<String,Object>();for(String key:keys)if(source.containsKey(key))result.put(key,source.get(key));return result;}
    private static String time(Object value){return value instanceof Timestamp stamp?stamp.toInstant().toString():null;}
    public void audit(UUID frame,String actor,String kind){
        if(!Set.of("detail","image").contains(kind))throw new IllegalArgumentException("无效的追溯访问类型");
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,"processing_trace."+kind,"read",frame);
    }
    public void auditVerification(Map<String,Object> result,String actor){
        var encoded=CanonicalJson.encode(Map.of("checked_at",result.get("checked_at"),"checks",result.get("checks")),32768);
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'processing_trace.verify',?,?)",actor,encoded.payload(),UUID.fromString((String)result.get("frame_id")));
    }
    private static Object safe(Object value,int depth){
        if(depth>16)throw new IllegalArgumentException("处理记录层级过深");
        if(value instanceof Map<?,?> map){var clean=new LinkedHashMap<String,Object>();for(var entry:map.entrySet())if(entry.getKey() instanceof String key&&!key.matches("(?i)(?:.*_)?(?:password|token|secret|credential|base64|url|path|file)|(?i)(?:source_ref|evidence_ref)"))clean.put(key,safe(entry.getValue(),depth+1));return clean;}
        if(value instanceof List<?> list){if(list.size()>1000)throw new IllegalArgumentException("处理记录超过限制");return list.stream().map(entry->safe(entry,depth+1)).toList();}
        return value;
    }
}
