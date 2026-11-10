package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import cn.trial.videosearch.models.ManagedCatalogStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** A profile ID never changes its vector space. Active binding may advance. */
@Service
public class AppearanceCatalog {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path path;
    private final Map<String,String> known=new HashMap<>();
    private long revision;private String digest;
    private long sourceRevision;private String sourceDigest;
    private final ObjectProvider<ManagedCatalogStore> managed;
    public AppearanceCatalog(@Value("${VIDEO_APPEARANCE_CATALOG_FILE:}")String path,ObjectProvider<ManagedCatalogStore> managed){this.path=path.isBlank()?null:Path.of(path);this.managed=managed;}
    public boolean configured(){return path!=null;}
    public record Profile(String id,String sha256,int dimensions,Map<String,Object> definition){}
    public record Snapshot(long revision,String active,Map<String,Profile> profiles) {public Profile selected(){return profiles.get(active);}}
    public synchronized Snapshot current(){
        var baseline=installed();
        try{
            var store=managed.getIfAvailable();var value=store==null?baseline.definition():store.resolve(baseline);
            var snapshot=parseSnapshot(value);String sha=CanonicalJson.encode(value,32768).sha256();
            if(snapshot.revision()<revision||snapshot.revision()==revision&&digest!=null&&!digest.equals(sha))throw new IllegalArgumentException();
            revision=snapshot.revision();digest=sha;return snapshot;
        }catch(InputRejected failure){throw failure;}
        catch(Exception failure){throw new InputRejected(503,"appearance_catalog_unavailable");}
    }
    public synchronized ManagedCatalogStore.Baseline installed(){
        try{
            if(path==null)throw new IllegalArgumentException();
            byte[] raw;try(var input=Files.newInputStream(path)){raw=input.readNBytes(32769);}
            if(raw.length>32768)throw new IllegalArgumentException();
            var value=object(JSON.readValue(raw,Object.class));
            var snapshot=parseSnapshot(value);long next=snapshot.revision();
            String sha=CanonicalJson.encode(value,32768).sha256();
            if(next<sourceRevision||next==sourceRevision&&sourceDigest!=null&&!sourceDigest.equals(sha))throw new IllegalArgumentException();
            for(var profile:snapshot.profiles().values())if(known.containsKey(profile.id())&&!known.get(profile.id()).equals(profile.sha256()))throw new IllegalArgumentException();
            snapshot.profiles().values().forEach(profile->known.put(profile.id(),profile.sha256()));sourceRevision=next;sourceDigest=sha;
            return new ManagedCatalogStore.Baseline("appearance",next,sha,Map.copyOf(value));
        }catch(Exception failure){throw new InputRejected(503,"appearance_catalog_unavailable");}
    }
    private static Snapshot parseSnapshot(Map<String,Object> value){
        keys(value,"schema_version","revision","active","profiles");
        if(!"appearance-catalog-v1".equals(value.get("schema_version")))throw new IllegalArgumentException();
        long next=integer(value.get("revision"),1,9007199254740991L);
        if(!(value.get("profiles") instanceof List<?> list)||list.isEmpty()||list.size()>8)throw new IllegalArgumentException();
        var profiles=new LinkedHashMap<String,Profile>();
        for(var entry:list){var profile=parse(object(entry));if(profiles.put(profile.id(),profile)!=null)throw new IllegalArgumentException();}
        if(!(value.get("active") instanceof String active)||!profiles.containsKey(active))throw new IllegalArgumentException();
        return new Snapshot(next,active,Map.copyOf(profiles));
    }
    static Profile parse(Map<String,Object> value){
        keys(value,"profile","adapter","model_file","model_sha256","dimensions","normalization","output","preprocessing");
        if(!(value.get("profile") instanceof String id)||!id.matches("[A-Za-z0-9_-]{1,80}")
            ||!"onnx_crop_v1".equals(value.get("adapter"))||!"roi_letterbox_224_rgb_v1".equals(value.get("preprocessing"))
            ||!(value.get("model_file") instanceof String file)||!file.matches("[A-Za-z0-9_-]+\\.onnx")||!hex(value.get("model_sha256"))
            ||!Set.of("imagenet","clip").contains(value.get("normalization"))||!Set.of("cls_token","image_embeds").contains(value.get("output")))throw new IllegalArgumentException();
        int dimensions=(int)integer(value.get("dimensions"),384,512);if(dimensions!=384&&dimensions!=512)throw new IllegalArgumentException();
        return new Profile(id,CanonicalJson.encode(value,8192).sha256(),dimensions,Map.copyOf(value));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException();return (Map<String,Object>)value;}
    static void keys(Map<String,Object> value,String... keys){if(!value.keySet().equals(Set.of(keys)))throw new IllegalArgumentException();}
    static long integer(Object value,long min,long max){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue()||n.longValue()<min||n.longValue()>max)throw new IllegalArgumentException();return n.longValue();}
    static boolean hex(Object value){return value instanceof String text&&text.matches("[a-f0-9]{64}");}
}
