package cn.trial.videosearch.support;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stable JSON for immutable payloads. Lists retain their evidence order. */
public final class CanonicalJson {
    private static final ObjectMapper JSON=new ObjectMapper();
    private CanonicalJson(){}
    public record Encoded(String payload,String sha256){}
    public static Encoded encode(Object value,int maxBytes){
        String payload=JSON.writeValueAsString(canonical(value));
        if(payload.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw new IllegalArgumentException("JSON exceeds bound");
        return new Encoded(payload,sha256(payload));
    }
    private static Object canonical(Object value){
        if(value instanceof Map<?,?> map){
            var sorted=new TreeMap<String,Object>();
            for(var entry:map.entrySet()){
                if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("JSON key must be a string");
                sorted.put(key,canonical(entry.getValue()));
            }
            return sorted;
        }
        if(value instanceof List<?> list)return list.stream().map(CanonicalJson::canonical).toList();
        if(value instanceof Double decimal&&!Double.isFinite(decimal)||value instanceof Float single&&!Float.isFinite(single))throw new IllegalArgumentException("JSON number must be finite");
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Number)return value;
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    public static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable",error);}
    }
}
