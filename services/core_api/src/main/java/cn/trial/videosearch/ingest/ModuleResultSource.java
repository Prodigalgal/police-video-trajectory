package cn.trial.videosearch.ingest;

import tools.jackson.databind.ObjectMapper;
import cn.trial.videosearch.support.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Canonical, immutable input for projections; no new model inference. */
public final class ModuleResultSource {
    public static final Set<String> KINDS=Set.of("person_attributes","vehicle_attributes","scene_context");
    private static final int MAX_BYTES=512*1024;
    private final ObjectMapper json=new ObjectMapper();
    public record Encoded(String payload,String sha256){}
    public static final class Invalid extends IllegalStateException {
        public Invalid(){super("saved module source is invalid");}
    }

    public Encoded encode(UUID frameId,TargetPipeline.Completion completion){
        var source=new LinkedHashMap<String,Object>();
        source.put("schema_version","analysis-result-source-v1");source.put("frame_id",frameId);
        source.put("model_id",completion.modelId());source.put("prompt_version",completion.promptVersion());
        source.put("targets",completion.targets());source.put("additional_objects",completion.additionalObjects());
        source.put("scene_description",completion.sceneDescription());
        return encodeValue(json.readValue(json.writeValueAsString(source),Object.class));
    }

    public Encoded encodeValue(Object value){
        try{var encoded=CanonicalJson.encode(value,MAX_BYTES);return new Encoded(encoded.payload(),encoded.sha256());}
        catch(IllegalArgumentException error){throw new Invalid();}
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> decode(String payload,String expected,UUID frameId){
        try {
            if(payload==null || expected==null || payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES ||
                    !(json.readValue(payload,Object.class) instanceof Map<?,?> source))throw new Invalid();
            var normalized=encodeValue(source);
            if(!MessageDigest.isEqual(normalized.sha256().getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII)) ||
                    !"analysis-result-source-v1".equals(source.get("schema_version")) || !frameId.toString().equals(source.get("frame_id")) ||
                    !(source.get("model_id") instanceof String) || !(source.get("prompt_version") instanceof String) ||
                    !(source.get("targets") instanceof List<?>) || !(source.get("additional_objects") instanceof List<?>))throw new Invalid();
            return (Map<String,Object>)source;
        }catch(Invalid error){throw error;}
        catch(RuntimeException error){throw new Invalid();}
    }

    public Map<String,Object> project(String kind,Map<String,Object> source){
        if(kind==null || !KINDS.contains(kind))throw new IllegalArgumentException("unsupported analysis module");
        var projected=new LinkedHashMap<String,Object>();
        projected.put("model_id",source.get("model_id"));projected.put("prompt_version",source.get("prompt_version"));
        if(kind.equals("scene_context")){
            projected.put("scene_description",source.get("scene_description"));
            projected.put("additional_objects",source.get("additional_objects"));
        }else{
            String type=kind.equals("person_attributes")?"person":"vehicle";
            var targets=(List<?>)source.get("targets");
            projected.put("targets",targets.stream().filter(raw->{
                if(!(raw instanceof Map<?,?> target))throw new Invalid();
                return type.equals(target.get("objectType"));
            }).toList());
        }
        return projected;
    }

    public static String sha256(String value){
        return CanonicalJson.sha256(value);
    }
}
