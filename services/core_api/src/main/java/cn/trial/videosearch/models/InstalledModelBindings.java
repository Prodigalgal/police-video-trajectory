package cn.trial.videosearch.models;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.stereotype.Service;
import java.util.*;

/** The management view exposes safe profile identities, never transport secrets. */
@Service
public class InstalledModelBindings {
    private final ProcessingCatalog processing;private final AppearanceCatalog appearance;private final ManagedCatalogStore store;
    public InstalledModelBindings(ProcessingCatalog processing,AppearanceCatalog appearance,ManagedCatalogStore store){this.processing=processing;this.appearance=appearance;this.store=store;}
    private ManagedCatalogStore.Baseline baseline(String kind){
        if("processing".equals(kind)){var installed=processing.installed();if(installed!=null)return installed;}
        else if("appearance".equals(kind)&&appearance.configured())return appearance.installed();
        throw new InputRejected(404,"model_catalog_unavailable");
    }
    private Map<String,Object> view(String kind){
        try{
            var baseline=baseline(kind);var saved=store.read(kind);boolean current=saved==null||saved.baseSha256().equals(baseline.sha256());
            var desired=saved==null?baseline.definition():saved.definition();
            if(current)ManagedCatalogStore.resolve(baseline,saved);
            var profiles=new ArrayList<Map<String,Object>>();
            for(Object raw:(List<?>)baseline.definition().get("profiles")){
                var profile=ManagedCatalogStore.object(raw);var safe=new LinkedHashMap<String,Object>();
                for(String field:List.of("profile","adapter","capabilities","model_id","dimensions","normalization","output"))if(profile.containsKey(field))safe.put(field,profile.get(field));
                safe.put("sha256",CanonicalJson.encode(profile,16384).sha256());profiles.add(safe);
            }
            return Map.of("id",kind,"available",true,"source_current",current,"base_sha256",baseline.sha256(),"base_revision",baseline.revision(),
                "revision",saved==null?0:saved.revision(),"effective_revision",saved==null?baseline.revision():saved.revision(),
                "bindings",ManagedCatalogStore.bindings(kind,desired),"profiles",profiles);
        }catch(InputRejected failure){return Map.of("id",kind,"available",false,"code",failure.code);}
    }
    public Map<String,Object> list(){return Map.of("schema_version","local-model-bindings-v1","catalogs",List.of(view("processing"),view("appearance")));}
    public Map<String,Object> save(String kind,ManagedCatalogStore.Command command,String actor){
        var baseline=baseline(kind);
        if(command==null||command.bindings()==null)throw new IllegalArgumentException();
        // File reads precede the write transaction. A concurrent file update is
        // detected by resolve() before any new job consumes the saved document.
        ManagedCatalogStore.materialize(baseline,command.bindings(),baseline.revision()+1);
        return store.save(baseline,command,actor);
    }
}
