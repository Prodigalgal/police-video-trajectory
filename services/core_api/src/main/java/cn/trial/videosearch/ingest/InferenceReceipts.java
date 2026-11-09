package cn.trial.videosearch.ingest;

import cn.trial.videosearch.support.CanonicalJson;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Internal receipt consistency. Hashes prove stored consistency, never recognition accuracy. */
public final class InferenceReceipts {
    public static final int MAX_BYTES=384*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FIELDS=Set.of("schema_version","receipt_id","gateway_version","source_revision","configured_model","model_binding","protocol","prompt_version","image_sha256","prompt_sha256","schema_sha256","schema_json","request_json","request_sha256","response_json","response_sha256","streaming","timeout_budget_ms","latency_ms","created_at","receipt_sha256");
    private InferenceReceipts(){}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw invalid();return (Map<String,Object>)value;}
    public static Map<String,Object> parse(String value){
        if(value==null||value.length()>MAX_BYTES)throw invalid();
        try{return object(JSON.readValue(value,Map.class));}catch(RuntimeException malformed){throw new IllegalArgumentException("识别回执 JSON 无效",malformed);}
    }
    public static Map<String,Object> validate(Map<String,Object> receipt,String image,String model,String prompt){
        if(model==null||prompt==null||receipt==null||!receipt.keySet().equals(FIELDS)||!"analysis-receipt-v1".equals(receipt.get("schema_version"))||!Boolean.TRUE.equals(receipt.get("streaming"))||!Objects.equals(image,receipt.get("image_sha256"))||!model.equals(receipt.get("configured_model"))||!prompt.equals(receipt.get("prompt_version")))throw invalid();
        UUID.fromString(text(receipt,"receipt_id",36));
        if(!text(receipt,"gateway_version",32).matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw invalid();
        if(receipt.get("source_revision")!=null&&!text(receipt,"source_revision",64).matches("[0-9a-f]{40,64}"))throw invalid();
        if(!Set.of("chat_completions","responses").contains(receipt.get("protocol")))throw invalid();
        try{java.time.Instant.parse(text(receipt,"created_at",80));}catch(java.time.DateTimeException invalidTime){throw new IllegalArgumentException("识别回执时间无效",invalidTime);}
        integer(receipt.get("timeout_budget_ms"),1,360000);integer(receipt.get("latency_ms"),0,370000);
        if(receipt.get("model_binding")!=null)text(receipt,"model_binding",2000);
        var unsigned=new LinkedHashMap<>(receipt);unsigned.remove("receipt_sha256");
        if(!CanonicalJson.encode(unsigned,MAX_BYTES).sha256().equals(text(receipt,"receipt_sha256",64)))throw invalid();
        String request=text(receipt,"request_json",MAX_BYTES),response=text(receipt,"response_json",MAX_BYTES);
        if(!CanonicalJson.sha256(request).equals(receipt.get("request_sha256"))||!CanonicalJson.sha256(response).equals(receipt.get("response_sha256")))throw invalid();
        var manifest=parse(request);
        if(!manifest.keySet().equals(Set.of("model","model_binding","protocol","stream","json_mode","image_transport","output_mode","reasoning_effort","prompt","result_schema_sha256"))||!model.equals(manifest.get("model"))||!Objects.equals(manifest.get("model_binding"),receipt.get("model_binding"))||!Objects.equals(manifest.get("protocol"),receipt.get("protocol"))||!Boolean.TRUE.equals(manifest.get("stream")))throw invalid();
        if(!CanonicalJson.sha256(text(manifest,"prompt",128*1024)).equals(receipt.get("prompt_sha256")))throw invalid();
        if(!CanonicalJson.sha256(text(receipt,"schema_json",128*1024)).equals(receipt.get("schema_sha256"))||!Objects.equals(manifest.get("result_schema_sha256"),receipt.get("schema_sha256")))throw invalid();
        return parse(response);
    }
    public static void matchTargets(Map<String,Object> response,TargetPipeline.Completion completion){
        if(!"2.0".equals(response.get("schema_version"))||!(response.get("targets") instanceof List<?> targets)||targets.size()!=completion.targets().size()||!Objects.equals(response.get("scene_description"),completion.sceneDescription()))throw invalid();
        for(var expected:completion.targets()){
            var matches=targets.stream().map(InferenceReceipts::object).filter(value->expected.targetId().toString().equals(value.get("target_id"))).toList();
            if(matches.size()!=1)throw invalid();var value=matches.getFirst();
            if(!Objects.equals(value.get("object_type"),expected.objectType())||!Objects.equals(value.get("visible"),expected.visible())||!Objects.equals(value.get("attributes"),expected.attributes())||!Objects.equals(value.get("description"),expected.description())||!Objects.equals(value.get("uncertain_fields"),expected.uncertainFields()))throw invalid();
        }
        if(!(response.get("additional_objects") instanceof List<?> additional))throw invalid();
        // Admission may discard additional regions, but it cannot invent or rewrite a reading.
        for(var expected:completion.additionalObjects())if(additional.stream().map(InferenceReceipts::object).noneMatch(value->sameObject(value,expected)))throw invalid();
    }
    public static void matchObjects(Map<String,Object> response,List<ObservationStore.VisibleObject> expected){
        if(!"1.0".equals(response.get("schema_version"))||!(response.get("objects") instanceof List<?> objects)||objects.size()!=expected.size())throw invalid();
        for(int i=0;i<expected.size();i++)if(!sameObject(object(objects.get(i)),expected.get(i)))throw invalid();
    }
    private static boolean sameObject(Map<String,Object> value,ObservationStore.VisibleObject expected){
        if(!Objects.equals(value.get("object_type"),expected.objectType())||!Objects.equals(value.get("attributes"),expected.attributes())||!Objects.equals(value.get("description"),expected.description())||!Objects.equals(value.get("uncertain_fields"),expected.uncertainFields()))return false;
        if(value.get("bbox_xyxy")==null)return expected.bboxXyxy()==null;
        if(!(value.get("bbox_xyxy") instanceof List<?> box)||expected.bboxXyxy()==null||box.size()!=4)return false;
        for(int i=0;i<4;i++)if(!(box.get(i) instanceof Number number)||Math.abs(number.doubleValue()-expected.bboxXyxy().get(i))>0.000001)return false;
        return true;
    }
    private static String text(Map<String,Object> value,String key,int max){if(!(value.get(key) instanceof String text)||text.isBlank()||text.length()>max)throw invalid();return text;}
    private static void integer(Object value,long low,long high){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<low||((Number)value).longValue()>high)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("识别回执与输入或结果不一致");}
}
