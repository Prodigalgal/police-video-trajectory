package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Final specialist admission. Never changes frame analysis or claims identity. */
@Service
public class SpecialistAdmission {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate db;
    private final boolean required;
    private final String fallbackPolicy;
    private final int windowSeconds;
    private final int cameraBudget;
    private final PlateEventResults events;
    public SpecialistAdmission(JdbcTemplate db,@Value("${VIDEO_SPECIALIST_SELECTION_REQUIRED:false}")boolean required,
        @Value("${VIDEO_SPECIALIST_SELECTION_POLICY_SHA256:}")String policy,
        @Value("${VIDEO_SPECIALIST_CAMERA_WINDOW_SECONDS:5}")int window,
        @Value("${VIDEO_SPECIALIST_CAMERA_BUDGET:2}")int budget,PlateEventResults events){
        if(!policy.isEmpty()&&!policy.matches("[a-f0-9]{64}")||window<1||window>300||budget<1||budget>100)throw new IllegalArgumentException("Invalid specialist admission policy");
        this.db=db;this.required=required;fallbackPolicy=policy;windowSeconds=window;cameraBudget=budget;this.events=events;
    }
    public boolean required(){return required;}
    public record Decision(Map<String,Object> summary,String policySha,String sourceSha){}
    private static final String REQUESTED_POLICY=CanonicalJson.sha256("requested-vehicle-plate-v1:minimum96x48");
    public Map<String,Object> previewRequested(Map<String,Object> descriptor,Map<String,Object> manifest,String intent){
        var decision=requested(descriptor,manifest,intent);var summary=new LinkedHashMap<>(decision.summary());
        if("selected".equals(summary.get("decision"))&&budgetFull(descriptor)){summary.put("decision","deferred");summary.put("business_state","budget_wait");summary.put("reasons",List.of("camera_budget_wait"));}return summary;
    }
    public Decision approveRequested(Map<String,Object> descriptor,Map<String,Object> manifest,String intent){
        var decision=requested(descriptor,manifest,intent);
        if(!"selected".equals(decision.summary().get("decision")))throw new InputRejected(409,"specialist_not_selected");
        if(budgetFull(descriptor))throw new InputRejected(429,"specialist_selection_budget");return decision;
    }
    private boolean budgetFull(Map<String,Object> descriptor){return db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions WHERE camera_id=? AND created_at>clock_timestamp()-(? * interval '1 second')",Long.class,descriptor.get("camera_id"),windowSeconds)>=cameraBudget;}
    private Decision requested(Map<String,Object> descriptor,Map<String,Object> manifest,String intent){
        if(!Set.of("verify_vehicle","plate_query").contains(intent)||!"license_plate".equals(descriptor.get("operation")))throw new IllegalArgumentException();
        var anchor=SpecialistResult.object(manifest.get("anchor"));UUID observation=UUID.fromString((String)anchor.get("observation_id"));
        var rows=db.queryForList("SELECT target_type,bbox_xyxy::text AS box FROM observations WHERE observation_id=? AND record_version=? AND frame_id=? AND camera_id=? AND NOT deleted",observation,anchor.get("record_version"),UUID.fromString((String)descriptor.get("frame_id")),descriptor.get("camera_id"));
        if(rows.size()!=1)throw new InputRejected(409,"input_source_changed");
        var row=rows.getFirst();Object rawBox=row.get("box")==null?null:JSON.readValue((String)row.get("box"),Object.class);List<Long> box=requestedBox(rawBox,((Number)descriptor.get("image_width")).longValue(),((Number)descriptor.get("image_height")).longValue());
        boolean vehicle="vehicle".equals(row.get("target_type")),usable=false;
        if(vehicle&&box.size()==4){
            long width=SpecialistResult.integer(descriptor.get("image_width"),1,20000),height=SpecialistResult.integer(descriptor.get("image_height"),1,20000);
            long x=SpecialistResult.integer(box.get(0),0,width-1),y=SpecialistResult.integer(box.get(1),0,height-1),right=SpecialistResult.integer(box.get(2),x+1,width),bottom=SpecialistResult.integer(box.get(3),y+1,height);
            usable=right-x>=96&&bottom-y>=48;
        }
        String reason=!vehicle?"requested_vehicle_required":!usable?"requested_vehicle_too_small":"requested_vehicle_check";
        var summary=new LinkedHashMap<String,Object>();summary.put("selection_required",true);summary.put("selection_mode","requested");summary.put("decision",usable?"selected":"skipped");summary.put("business_state",usable?"need_read":"not_applicable");summary.put("reasons",List.of(reason));summary.put("modules",List.of(Map.of("module","license_plate","decision",usable?"selected":"skipped","reasons",List.of(reason))));
        summary.put("selected_observation_ids",usable?List.of(observation.toString()):List.of());summary.put("policy_sha256",REQUESTED_POLICY);summary.put("frame_id",descriptor.get("frame_id"));summary.put("jpeg_sha256",descriptor.get("evidence_sha256"));summary.put("vehicle_bbox_xyxy",box);summary.put("intent",intent);summary.put("identity_association_verified",false);summary.put("recommendation_is_inference_completion",false);
        return new Decision(summary,REQUESTED_POLICY,CanonicalJson.encode(Map.of("input_sha256",descriptor.get("input_manifest_sha256"),"observation_id",observation.toString(),"intent",intent,"box",box),8192).sha256());
    }
    static List<Long> requestedBox(Object raw,long width,long height){
        if(!(raw instanceof List<?> box)||box.size()!=4||box.stream().anyMatch(value->!(value instanceof Number number)||!Double.isFinite(number.doubleValue())))return List.of();
        double x=((Number)box.get(0)).doubleValue(),y=((Number)box.get(1)).doubleValue(),right=((Number)box.get(2)).doubleValue(),bottom=((Number)box.get(3)).doubleValue();
        if(x<0||y<0||right<=x||bottom<=y||right>width||bottom>height)return List.of();
        return List.of((long)Math.floor(x),(long)Math.floor(y),(long)Math.ceil(right),(long)Math.ceil(bottom));
    }
    public Map<String,Object> preview(Map<String,Object> descriptor,ProcessingCatalog.Binding binding){
        var result=new LinkedHashMap<>(evaluate(descriptor,binding).summary());
        if(required&&"selected".equals(result.get("decision"))&&db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions WHERE camera_id=? AND created_at>clock_timestamp()-(? * interval '1 second')",Long.class,descriptor.get("camera_id"),windowSeconds)>=cameraBudget){
            result.put("decision","deferred");result.put("business_state","budget_wait");result.put("reasons",List.of("camera_budget_wait"));
        }
        return result;
    }
    public Decision approve(Map<String,Object> descriptor,ProcessingCatalog.Binding binding){
        if(!required||!"license_plate".equals(descriptor.get("operation")))return null;
        var decision=evaluate(descriptor,binding);
        if("unavailable".equals(decision.summary().get("decision")))throw new InputRejected(409,"specialist_selection_unavailable");
        if("reused".equals(decision.summary().get("decision")))throw new InputRejected(409,"specialist_result_reusable");
        if(!"selected".equals(decision.summary().get("decision")))throw new InputRejected(409,"specialist_not_selected");
        long count=db.queryForObject("SELECT count(*) FROM specialist_analysis_admissions WHERE camera_id=? AND created_at>clock_timestamp()-(? * interval '1 second')",Long.class,descriptor.get("camera_id"),windowSeconds);
        if(count>=cameraBudget)throw new InputRejected(429,"specialist_selection_budget");
        return decision;
    }
    public void record(Decision decision,UUID request,UUID job,Map<String,Object> descriptor,String action){
        if(decision==null)return;
        db.update("INSERT INTO specialist_analysis_admissions(request_id,job_id,frame_id,camera_id,action,policy_sha256,selection_sha256,decision) VALUES(?,?,?,?,?,?,?,?::jsonb)",
            request,job,UUID.fromString((String)descriptor.get("frame_id")),descriptor.get("camera_id"),action,decision.policySha(),decision.sourceSha(),CanonicalJson.encode(decision.summary(),32768).payload());
    }
    private Decision unavailable(String reason){return new Decision(Map.of("selection_required",required,"decision","unavailable","business_state","waiting_evidence","reasons",List.of(reason),"modules",List.of()),"","");}
    private Decision evaluate(Map<String,Object> descriptor,ProcessingCatalog.Binding binding){
        try{
            UUID frame=UUID.fromString((String)descriptor.get("frame_id"));
            var rows=db.queryForList("SELECT frame_id,camera_id,scene_run_id,media_epoch,relative_media_ms,payload_sha256,plan::text AS plan_text FROM capture_outbox WHERE frame_id=?",frame);
            if(rows.size()!=1)return unavailable("selection_evidence_missing");
            var source=rows.getFirst();var plan=SpecialistResult.object(JSON.readValue((String)source.get("plan_text"),Object.class));
            Object rawMetrics=plan.get("selection_metrics");
            if(!(rawMetrics instanceof Map<?,?> metrics)||!(metrics.get("specialist_selection") instanceof Map<?,?> raw))return unavailable("selection_evidence_missing");
            var selection=SpecialistResult.object(raw);String expected=binding.catalogRevision()>0?binding.selectionPolicy():fallbackPolicy;
            if(expected.isEmpty()||!expected.equals(selection.get("policy_sha256")))return unavailable("selection_policy_unavailable");
            validateSource(descriptor,source,plan,selection);
            if(!"select".equals(selection.get("mode"))||!"complete".equals(selection.get("state")))return unavailable("selection_not_applied");
            if(!(selection.get("modules") instanceof List<?> modules)||modules.size()!=2)throw new IllegalArgumentException();
            Map<String,Object> plate=null;var output=new ArrayList<Map<String,Object>>();var kinds=new HashSet<String>();
            for(Object value:modules){
                var module=SpecialistResult.object(value);String name=SpecialistResult.text(module.get("module"),40);
                if(!kinds.add(name)||!Set.of("license_plate","face_quality").contains(name))throw new IllegalArgumentException();
                String state=SpecialistResult.text(module.get("decision"),20);if(!Set.of("selected","skipped","deferred").contains(state))throw new IllegalArgumentException();
                var reasons=reasons(module.get("reasons"));
                if(name.equals("license_plate")){plate=module;validateTargets(descriptor,plan,module);}
                else if(!Boolean.FALSE.equals(module.get("identity_matching"))||!Boolean.FALSE.equals(module.get("operation_available"))||!"deferred".equals(state)&&!"skipped".equals(state))throw new IllegalArgumentException();
                output.add(Map.of("module",name,"decision",state,"reasons",reasons));
            }
            if(plate==null)throw new IllegalArgumentException();
            var summary=new LinkedHashMap<String,Object>();summary.put("selection_required",required);summary.put("decision",plate.get("decision"));summary.put("reasons",explanations(plate));
            summary.put("modules",output);summary.put("selected_target_ids",plate.get("selected_target_ids"));summary.put("policy_sha256",expected);
            summary.put("frame_id",descriptor.get("frame_id"));summary.put("jpeg_sha256",descriptor.get("evidence_sha256"));
            summary.put("recommendation_is_inference_completion",false);summary.put("identity_association_verified",false);
            summary.put("business_state",plate.get("business_state"));
            summary.putAll(eventFacts(descriptor,plan,selection,plate));
            return new Decision(events.assess(summary,descriptor),expected,CanonicalJson.encode(selection,128*1024).sha256());
        }catch(org.springframework.dao.DataAccessException failure){throw new InputRejected(503,"specialist_selection_unavailable");}
        catch(Exception invalid){return unavailable("selection_evidence_invalid");}
    }
    static void validateSource(Map<String,Object> descriptor,Map<String,Object> source,Map<String,Object> plan,Map<String,Object> selection){
        if(!"specialist-selection-v2".equals(selection.get("schema_version"))||!Boolean.FALSE.equals(selection.get("frame_suppression"))
            ||!Boolean.FALSE.equals(selection.get("semantic_modules_changed"))||!Boolean.FALSE.equals(selection.get("original_targets_changed")))throw new IllegalArgumentException();
        for(var entry:Map.of("frame_id","frame_id","camera_id","camera_id","jpeg_sha256","payload_sha256","scene_run_id","scene_run_id").entrySet()){
            Object expected=source.get(entry.getValue());if(expected==null||!expected.toString().equals(selection.get(entry.getKey())))throw new IllegalArgumentException();
        }
        if(!Objects.equals(descriptor.get("frame_id"),selection.get("frame_id"))||!Objects.equals(descriptor.get("camera_id"),selection.get("camera_id"))||!Objects.equals(descriptor.get("evidence_sha256"),selection.get("jpeg_sha256")))throw new IllegalArgumentException();
        for(String key:List.of("media_epoch","relative_media_ms"))if(number(source.get(key))!=number(selection.get(key)))throw new IllegalArgumentException();
        for(String key:List.of("image_width","image_height"))if(number(descriptor.get(key))!=number(selection.get(key))||number(plan.get(key))!=number(selection.get(key)))throw new IllegalArgumentException();
    }
    private static void validateTargets(Map<String,Object> descriptor,Map<String,Object> plan,Map<String,Object> module){
        if(!(module.get("selected_target_ids") instanceof List<?> selected)||selected.size()>1||new HashSet<>(selected).size()!=selected.size()
            ||!(module.get("targets") instanceof List<?> decisions)||decisions.size()>100||!(plan.get("targets") instanceof List<?> targets))throw new IllegalArgumentException();
        if("selected".equals(module.get("decision"))!=!selected.isEmpty())throw new IllegalArgumentException();
        var available=new HashMap<String,Map<String,Object>>();
        for(Object raw:targets){var target=SpecialistResult.object(raw);available.put((String)target.get("target_id"),target);}
        var recommendations=new HashMap<String,Map<String,Object>>();
        for(Object raw:decisions){var candidate=SpecialistResult.object(raw);String id=SpecialistResult.text(candidate.get("target_id"),36);if(recommendations.put(id,candidate)!=null)throw new IllegalArgumentException();}
        for(Object value:selected){
            if(!(value instanceof String id)||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();
            var target=available.get(id);var candidate=recommendations.get(id);
            if(target==null||candidate==null||!"vehicle".equals(target.get("object_type"))||!Set.of("car","motorcycle","bus","truck").contains(target.get("class_name"))
                ||!"local_geometry_candidate".equals(target.get("association"))||!Objects.equals(target.get("track_id"),candidate.get("track_id"))||!"selected".equals(candidate.get("decision")))throw new IllegalArgumentException();
            var representative=SpecialistResult.object(candidate.get("representative"));
            if(!Objects.equals(descriptor.get("frame_id"),representative.get("frame_id"))||!Objects.equals(descriptor.get("evidence_sha256"),representative.get("jpeg_sha256")))throw new IllegalArgumentException();
        }
    }
    private static long number(Object value){return SpecialistResult.integer(value,0,Long.MAX_VALUE);}
    private static Map<String,Object> eventFacts(Map<String,Object> descriptor,Map<String,Object> plan,Map<String,Object> selection,Map<String,Object> module)throws Exception{
        if(!Set.of("not_applicable","waiting_evidence","need_read","waiting_result").contains(module.get("business_state")))throw new IllegalArgumentException();
        if("selected".equals(module.get("decision"))&&!"need_read".equals(module.get("business_state")))throw new IllegalArgumentException();
        var available=new HashMap<String,Map<String,Object>>();for(Object value:(List<?>)plan.get("targets")){var target=SpecialistResult.object(value);available.put((String)target.get("target_id"),target);}
        var selected=(List<?>)module.get("selected_target_ids");
        var candidates=new ArrayList<Map<String,Object>>();
        for(Object value:(List<?>)module.get("targets")){
            var candidate=SpecialistResult.object(value);String targetId=(String)candidate.get("target_id");
            if(!selected.isEmpty()?!selected.contains(targetId):!candidate.containsKey("event_id")||"not_applicable".equals(candidate.get("business_state")))continue;
            candidates.add(candidate);
        }
        // A frame containing multiple unselected events cannot advertise one event's result as the whole frame's result.
        if(selected.isEmpty()&&candidates.size()!=1)return Map.of();
        for(var candidate:candidates){
            var target=available.get(candidate.get("target_id"));
            if(target==null||!"vehicle".equals(target.get("object_type"))||!"local_geometry_candidate".equals(target.get("association")))throw new IllegalArgumentException();
            String track=(String)target.get("track_id"),event=eventId((String)descriptor.get("camera_id"),(String)selection.get("scene_run_id"),number(selection.get("media_epoch")),track);
            if(!UUID.fromString(track).toString().equals(track)||!event.equals(candidate.get("event_id"))||!Objects.equals(track,candidate.get("track_id")))throw new IllegalArgumentException();
            String intent=SpecialistResult.text(candidate.get("intent"),40);if(!Set.of("collect_vehicle_passages","plate_query","verify_vehicle").contains(intent))throw new IllegalArgumentException();
            var facts=new LinkedHashMap<String,Object>(Map.of("event_id",event,"intent",intent,"scene_run_id",selection.get("scene_run_id"),"media_epoch",selection.get("media_epoch"),"relative_media_ms",selection.get("relative_media_ms")));
            if(!selected.isEmpty()){
                if("collect_vehicle_passages".equals(intent)&&!Set.of("moving_observed","recent_moving_observed").contains(candidate.get("passage_evidence")))throw new IllegalArgumentException();
                facts.put("plate_bbox_xyxy",validatePlate(descriptor,available.values(),target,candidate));
            }
            return facts;
        }
        if(!selected.isEmpty())throw new IllegalArgumentException();return Map.of();
    }
    private static List<?> validatePlate(Map<String,Object> descriptor,Collection<Map<String,Object>> targets,Map<String,Object> target,Map<String,Object> candidate){
        var plate=SpecialistResult.object(candidate.get("plate_evidence"));
        if(!"plate-evidence-v1".equals(plate.get("schema_version"))||!"opencv_plate_v1".equals(plate.get("adapter"))
            ||!"unique_spatial_containment".equals(plate.get("association"))||!Objects.equals(descriptor.get("frame_id"),plate.get("frame_id"))
            ||!Objects.equals(descriptor.get("evidence_sha256"),plate.get("jpeg_sha256"))||!(plate.get("model_sha256") instanceof String sha)||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
        if(!(plate.get("bbox_xyxy") instanceof List<?> box)||box.size()!=4)throw new IllegalArgumentException();
        long left=number(box.get(0)),top=number(box.get(1)),right=number(box.get(2)),bottom=number(box.get(3));
        if(left>=right||top>=bottom||right>number(descriptor.get("image_width"))||bottom>number(descriptor.get("image_height")))throw new IllegalArgumentException();
        if(!(plate.get("localization_score") instanceof Number score)||!Double.isFinite(score.doubleValue())||score.doubleValue()<0||score.doubleValue()>1
            ||!"localized_plate_not_character_correctness".equals(candidate.get("quality_scope"))
            ||nativePixels(candidate.get("native_width_px"))!=right-left||nativePixels(candidate.get("native_height_px"))!=bottom-top
            ||!(candidate.get("readability_rank") instanceof Number rank)||!Double.isFinite(rank.doubleValue())||rank.doubleValue()<0||rank.doubleValue()>1)throw new IllegalArgumentException();
        if(!containsPlate(target,box)||targets.stream().filter(t->"vehicle".equals(t.get("object_type"))&&containsPlate(t,box)).count()!=1)throw new IllegalArgumentException();
        return box;
    }
    private static long nativePixels(Object value){
        // Python quality metrics retain float pixel spans; integer-valued
        // spans are compatible, while source IDs, clocks and boxes stay strict.
        if(!(value instanceof Number span)||!Double.isFinite(span.doubleValue())||span.doubleValue()<1
            ||span.doubleValue()>20000||span.doubleValue()!=Math.rint(span.doubleValue()))throw new IllegalArgumentException();
        return span.longValue();
    }
    private static boolean containsPlate(Map<String,Object> vehicle,List<?> box){
        if(!(vehicle.get("bbox_xyxy") instanceof List<?> bounds)||bounds.size()!=4)throw new IllegalArgumentException();
        double left=((Number)box.get(0)).doubleValue(),top=((Number)box.get(1)).doubleValue(),right=((Number)box.get(2)).doubleValue(),bottom=((Number)box.get(3)).doubleValue();
        double intersection=Math.max(0,Math.min(right,((Number)bounds.get(2)).doubleValue())-Math.max(left,((Number)bounds.get(0)).doubleValue()))*
            Math.max(0,Math.min(bottom,((Number)bounds.get(3)).doubleValue())-Math.max(top,((Number)bounds.get(1)).doubleValue()));
        return intersection/((right-left)*(bottom-top))>=.8;
    }
    public static String eventId(String camera,String run,long epoch,String track){
        String name="plate-event:"+camera+":"+run+":"+epoch+":"+track;
        var namespace=UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");byte[] label=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var bytes=java.nio.ByteBuffer.allocate(16+label.length).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).put(label).array();
        byte[] hash;try{hash=java.security.MessageDigest.getInstance("SHA-1").digest(bytes);}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("UUID digest is unavailable",unavailable);}
        hash[6]=(byte)((hash[6]&15)|80);hash[8]=(byte)((hash[8]&63)|128);
        var buffer=java.nio.ByteBuffer.wrap(hash);return new UUID(buffer.getLong(),buffer.getLong()).toString();
    }
    private static List<String> reasons(Object value){
        if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>12)throw new IllegalArgumentException();
        var result=new ArrayList<String>();for(Object raw:list){if(!(raw instanceof String text)||!text.matches("[a-z_]{1,80}"))throw new IllegalArgumentException();result.add(text);}return result;
    }
    private static List<String> explanations(Map<String,Object> module){
        var explanation=new LinkedHashSet<>(reasons(module.get("reasons")));
        for(Object raw:(List<?>)module.get("targets")){
            var candidate=SpecialistResult.object(raw);
            if(candidate.get("reasons") instanceof List<?>){
                for(String reason:reasons(candidate.get("reasons")))if(!"not_a_vehicle_candidate".equals(reason))explanation.add(reason);
            }
        }
        return explanation.stream().limit(12).toList();
    }
}
