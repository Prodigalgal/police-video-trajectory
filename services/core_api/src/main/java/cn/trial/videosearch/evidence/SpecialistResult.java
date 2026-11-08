package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import java.util.*;

/** Dedicated-engine declarations, never promoted to reviewed identity or ES facts. */
public final class SpecialistResult {
    private SpecialistResult(){}
    public static final Set<String> OPERATIONS=Set.of("text_ocr","license_plate");
    public static void keys(Map<?,?> value,String...names){if(value==null||!value.keySet().equals(Set.of(names)))throw invalid();}
    public static long integer(Object raw,long low,long high){if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<low||((Number)raw).longValue()>high)throw invalid();return ((Number)raw).longValue();}
    public static String text(Object value,int maximum){if(!(value instanceof String text)||text.isBlank()||text.length()>maximum||text.codePoints().anyMatch(n->Character.isISOControl(n)&&n!='\n'&&n!='\t'))throw invalid();return text;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> map))throw invalid();return (Map<String,Object>)map;}
    public static Map<String,Object> input(UUID id,AnalysisInputs.Verified verified,String operation,String profile){
        try{return inputValue(id,verified,operation,profile);}catch(InputRejected error){throw error;}catch(RuntimeException error){throw invalid();}
    }
    private static Map<String,Object> inputValue(UUID id,AnalysisInputs.Verified verified,String operation,String profile){
        if(operation==null||!OPERATIONS.contains(operation)||profile==null||!profile.matches("[A-Za-z0-9_.-]{1,80}"))throw invalid();var manifest=verified.manifest();
        if(!"single_frame".equals(manifest.get("purpose"))||!(manifest.get("frames") instanceof List<?> frames)||frames.size()!=1)throw new InputRejected(422,"specialist_single_frame_required");
        var frame=object(frames.getFirst());var source=object(manifest.get("source"));var anchor=object(manifest.get("anchor"));
        String frameId=text(frame.get("frame_id"),36);if(!frameId.equals(UUID.fromString(frameId).toString())||!frameId.equals(anchor.get("frame_id")))throw invalid();
        String camera=text(source.get("camera_id"),100),sha=text(frame.get("payload_sha256"),64);if(!sha.matches("[a-f0-9]{64}"))throw invalid();
        long width=integer(frame.get("image_width"),1,20000),height=integer(frame.get("image_height"),1,20000);if(width*height>50000000)throw invalid();
        return Map.of("schema_version","specialist-input-v1","input_id",id.toString(),"input_manifest_sha256",verified.sha256(),"frame_id",frameId,"camera_id",camera,"evidence_sha256",sha,"image_width",width,"image_height",height,"operation",operation,"profile",profile);
    }
    public static CanonicalJson.Encoded validate(UUID job,String inputSha,Map<String,Object> input,Map<String,Object> result){
        try{return validateValue(job,inputSha,input,result);}catch(InputRejected error){throw error;}catch(RuntimeException error){throw invalid();}
    }
    private static CanonicalJson.Encoded validateValue(UUID job,String inputSha,Map<String,Object> input,Map<String,Object> result){
        keys(result,"schema_version","job_id","input_sha256","frame_id","evidence_sha256","operation","profile","image_width","image_height","engine","outcome","regions","limitations");
        if(!"specialist-result-v1".equals(result.get("schema_version"))||!job.toString().equals(result.get("job_id"))||!inputSha.equals(result.get("input_sha256")))throw invalid();
        for(String key:List.of("frame_id","evidence_sha256","operation","profile"))if(!input.get(key).equals(result.get(key)))throw invalid();
        long width=integer(result.get("image_width"),1,20000),height=integer(result.get("image_height"),1,20000);
        if(width!=((Number)input.get("image_width")).longValue()||height!=((Number)input.get("image_height")).longValue())throw invalid();
        var engine=object(result.get("engine"));keys(engine,"name","version");text(engine.get("name"),100);text(engine.get("version"),100);
        if(!Set.of("recognized","no_text","unreadable").contains(result.get("outcome"))||!(result.get("regions") instanceof List<?> regions)||regions.size()>100||("recognized".equals(result.get("outcome"))==regions.isEmpty()))throw invalid();
        int index=0;for(Object raw:regions){var region=object(raw);keys(region,"index","text","bbox_xyxy","score");integer(region.get("index"),index,index);index++;text(region.get("text"),"license_plate".equals(input.get("operation"))?64:512);
            if(!(region.get("bbox_xyxy") instanceof List<?> box)||box.size()!=4)throw invalid();long x=integer(box.get(0),0,width-1),y=integer(box.get(1),0,height-1);integer(box.get(2),x+1,width);integer(box.get(3),y+1,height);
            Object score=region.get("score");if(score!=null&&(!(score instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>1))throw invalid();
        }
        if(!(result.get("limitations") instanceof List<?> limitations)||limitations.size()>8||new HashSet<>(limitations).size()!=limitations.size()||!Set.of("low_resolution","blur","occlusion","unsupported_language","uncalibrated_score","uncertain_characters","partial_text","inconsistent_readings").containsAll(limitations))throw invalid();
        if("license_plate".equals(input.get("operation"))&&"recognized".equals(result.get("outcome"))&&limitations.stream().anyMatch(Set.of("uncertain_characters","partial_text","inconsistent_readings")::contains))throw invalid();
        try{return CanonicalJson.encode(result,128*1024);}catch(IllegalArgumentException error){throw invalid();}
    }
    static InputRejected invalid(){return new InputRejected(422,"invalid_specialist_result");}
}
