package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import cn.trial.videosearch.models.ManagedCatalogStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Read-only versioned bindings. File I/O happens before job write transactions. */
@Service
public class ProcessingCatalog {
    private static final int MAX_BYTES=128*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String ADAPTER="specialist_sse_v1";
    private final Path path;
    private final Map<String,String> definitions=new HashMap<>();
    private long lastRevision;
    private String lastSha;
    private long sourceRevision;private String sourceSha;
    private final ObjectProvider<ManagedCatalogStore> managed;

    public ProcessingCatalog(@Value("${VIDEO_PROCESSING_CATALOG_FILE:}")String file,ObjectProvider<ManagedCatalogStore> managed){path=file.isBlank()?null:Path.of(file);this.managed=managed;}
    public record Snapshot(long revision,String sha256,Map<String,Object> modules,Map<String,Map<String,Object>> profiles){}
    public record Binding(String profile,Map<String,Object> route,CanonicalJson.Encoded encoded,String selectionPolicy,long catalogRevision){}
    public boolean configured(){return path!=null;}

    public synchronized Snapshot current(){
        var baseline=installed();if(baseline==null)return null;
        try{
            var store=managed.getIfAvailable();var value=store==null?baseline.definition():store.resolve(baseline);
            Snapshot snapshot=parse(value);
            if(snapshot.revision()<lastRevision||snapshot.revision()==lastRevision&&lastSha!=null&&!lastSha.equals(snapshot.sha256()))throw new IllegalArgumentException();
            lastRevision=snapshot.revision();lastSha=snapshot.sha256();return snapshot;
        }catch(InputRejected failure){throw failure;}
        catch(Exception failure){throw new InputRejected(503,"processing_catalog_unavailable");}
    }

    public synchronized ManagedCatalogStore.Baseline installed(){
        if(path==null)return null;
        try{
            byte[] raw;
            try(var stream=Files.newInputStream(path)){raw=stream.readNBytes(MAX_BYTES+1);}
            if(raw.length==0||raw.length>MAX_BYTES)throw new IllegalArgumentException();
            var value=SpecialistResult.object(JSON.readValue(raw,Object.class));Snapshot snapshot=parse(value);
            if(snapshot.revision()<sourceRevision||snapshot.revision()==sourceRevision&&sourceSha!=null&&!sourceSha.equals(snapshot.sha256()))throw new IllegalArgumentException();
            for(var entry:snapshot.profiles().entrySet()){
                String sha=CanonicalJson.encode(entry.getValue(),16384).sha256();
                if(definitions.containsKey(entry.getKey())&&!sha.equals(definitions.get(entry.getKey())))throw new IllegalArgumentException();
            }
            for(var entry:snapshot.profiles().entrySet())definitions.put(entry.getKey(),CanonicalJson.encode(entry.getValue(),16384).sha256());
            sourceRevision=snapshot.revision();sourceSha=snapshot.sha256();
            return new ManagedCatalogStore.Baseline("processing",snapshot.revision(),snapshot.sha256(),Map.copyOf(value));
        }catch(Exception failure){throw new InputRejected(503,"processing_catalog_unavailable");}
    }

    static Snapshot parse(Map<String,Object> value){
        SpecialistResult.keys(value,"schema_version","revision","modules","profiles");
        boolean extended="processing-catalog-v2".equals(value.get("schema_version"));
        if(!extended&&!"processing-catalog-v1".equals(value.get("schema_version")))throw new IllegalArgumentException();
        long revision=SpecialistResult.integer(value.get("revision"),1,9007199254740991L);
        var modules=SpecialistResult.object(value.get("modules"));
        if(modules.size()>(extended?16:4)||!(value.get("profiles") instanceof List<?> rawProfiles)||rawProfiles.size()>32)throw new IllegalArgumentException();
        var profiles=new LinkedHashMap<String,Map<String,Object>>();
        for(Object raw:rawProfiles){var definition=profile(SpecialistResult.object(raw));String key=(String)definition.get("profile");if(profiles.put(key,definition)!=null)throw new IllegalArgumentException();}
        for(var entry:modules.entrySet()){
            String name=entry.getKey();var module=SpecialistResult.object(entry.getValue());
            if(!(module.get("enabled") instanceof Boolean enabled))throw new IllegalArgumentException();
            if(extended&&Set.of("temporal_aggregation","candidate_association").contains(name)){
                SpecialistResult.keys(module,"enabled","plugin");
                if(!(module.get("plugin") instanceof String plugin)||!token(plugin))throw new IllegalArgumentException();
            }else if(name.equals("specialist_selection")){
                if(!module.keySet().equals(Set.of("enabled","plugin","mode","policy_sha256"))&&!module.keySet().equals(Set.of("enabled","plugin","mode","policy_sha256","plate_probe")))throw new IllegalArgumentException();
                if(!"rules_v1".equals(module.get("plugin"))||!Set.of("observe","select").contains(module.get("mode"))||!hex(module.get("policy_sha256")))throw new IllegalArgumentException();
                if(module.get("plate_probe")!=null){var probe=SpecialistResult.object(module.get("plate_probe"));SpecialistResult.keys(probe,"adapter","model_file","model_sha256");
                    if(!"opencv_plate_v1".equals(probe.get("adapter"))||!(probe.get("model_file") instanceof String model)||!token(model)||Set.of(".","..").contains(model)||!hex(probe.get("model_sha256")))throw new IllegalArgumentException();}
            }else if(name.equals("face_quality")){
                SpecialistResult.keys(module,"enabled");if(enabled)throw new IllegalArgumentException();
            }else if(SpecialistResult.OPERATIONS.contains(name)){
                SpecialistResult.keys(module,"enabled","profile");Object selected=module.get("profile");
                if(selected!=null&&(!(selected instanceof String text)||!token(text)))throw new IllegalArgumentException();
                var definition=profiles.get(selected);
                if(enabled&&(definition==null||!((List<?>)definition.get("capabilities")).contains(name))||!enabled&&selected!=null&&definition==null)throw new IllegalArgumentException();
            }else throw new IllegalArgumentException();
        }
        return new Snapshot(revision,CanonicalJson.encode(value,MAX_BYTES).sha256(),modules,profiles);
    }

    public Binding binding(String operation,String legacy){return binding(current(),operation,legacy);}
    public Binding binding(Snapshot snapshot,String operation,String legacy){
        if(snapshot==null)return new Binding(legacy,null,null,"",0);
        var raw=snapshot.modules().get(operation);
        if(raw==null||!Boolean.TRUE.equals(SpecialistResult.object(raw).get("enabled")))throw new InputRejected(409,"specialist_module_disabled");
        String id=(String)SpecialistResult.object(raw).get("profile");var definition=snapshot.profiles().get(id);
        var route=Map.<String,Object>of("schema_version","specialist-route-v1","catalog_revision",snapshot.revision(),"catalog_sha256",snapshot.sha256(),"definition",definition);
        return new Binding(id,route,CanonicalJson.encode(route,32768),selectionPolicy(snapshot),snapshot.revision());
    }
    public String selectionPolicy(Snapshot snapshot){
        if(snapshot==null)return "";Object selection=snapshot.modules().get("specialist_selection");
        if(selection instanceof Map<?,?> module&&Boolean.TRUE.equals(module.get("enabled"))&&"select".equals(module.get("mode")))return (String)module.get("policy_sha256");
        return "";
    }

    public String strategy(Snapshot snapshot,String module,String fallback,Set<String> installed){
        String plugin=fallback;
        if(snapshot!=null&&snapshot.modules().containsKey(module)){
            var definition=SpecialistResult.object(snapshot.modules().get(module));
            if(!Boolean.TRUE.equals(definition.get("enabled")))throw new InputRejected(409,"processing_module_disabled");
            plugin=(String)definition.get("plugin");
        }
        if(plugin==null||!installed.contains(plugin))throw new InputRejected(503,"processing_plugin_unavailable");
        return plugin;
    }

    public static Map<String,Object> profile(Map<String,Object> definition){
        SpecialistResult.keys(definition,"profile","adapter","capabilities","base_url","token_file","ca_file","model_id","contract","streaming","timeout_seconds");
        if(!(definition.get("profile") instanceof String id)||!token(id)||!ADAPTER.equals(definition.get("adapter"))||!"specialist-result-v1".equals(definition.get("contract"))
            ||!Boolean.TRUE.equals(definition.get("streaming"))||SpecialistResult.integer(definition.get("timeout_seconds"),360,360)!=360)throw new IllegalArgumentException();
        if(!(definition.get("capabilities") instanceof List<?> caps)||caps.isEmpty()||caps.size()>2||new HashSet<>(caps).size()!=caps.size()||!SpecialistResult.OPERATIONS.containsAll(caps))throw new IllegalArgumentException();
        for(String key:List.of("token_file","ca_file"))if(!(definition.get(key) instanceof String file)||!token(file)||Set.of(".","..").contains(file))throw new IllegalArgumentException();
        String model=SpecialistResult.text(definition.get("model_id"),100);if(model.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
        String address=SpecialistResult.text(definition.get("base_url"),512);var uri=URI.create(address);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()>65535)throw new IllegalArgumentException();
        return definition;
    }
    private static boolean token(String value){return value.matches("[A-Za-z0-9_.-]{1,80}");}
    private static boolean hex(Object value){return value instanceof String text&&text.matches("[a-f0-9]{64}");}
}
