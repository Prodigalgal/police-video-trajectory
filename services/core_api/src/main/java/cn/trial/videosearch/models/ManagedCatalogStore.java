package cn.trial.videosearch.models;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Trusted file definitions and operator selections have independent ownership. */
@Service
public class ManagedCatalogStore {
    public record Baseline(String kind,long revision,String sha256,Map<String,Object> definition){}
    public record Saved(long revision,String baseSha256,String sha256,Map<String,Object> definition){}
    public record Command(UUID requestId,long expectedRevision,String baseSha256,Map<String,String> bindings){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final long MAX_REVISION=9007199254740991L;
    private final JdbcTemplate db;
    public ManagedCatalogStore(JdbcTemplate db){this.db=db;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value){
        if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Model object required");return (Map<String,Object>)value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> decode(String value){return JSON.readValue(value,Map.class);}
    public Saved read(String kind){
        var rows=db.queryForList("SELECT revision,base_sha256,definition_sha256,definition::text AS definition FROM managed_model_catalogs WHERE catalog_key=?",kind);
        if(rows.isEmpty())return null;var row=rows.getFirst();
        return new Saved(((Number)row.get("revision")).longValue(),(String)row.get("base_sha256"),(String)row.get("definition_sha256"),decode((String)row.get("definition")));
    }
    public Map<String,Object> resolve(Baseline baseline){return resolve(baseline,read(baseline.kind()));}
    public static Map<String,Object> resolve(Baseline baseline,Saved saved){
        if(saved==null)return baseline.definition();
        if(!baseline.sha256().equals(saved.baseSha256()))throw new InputRejected(409,"model_catalog_source_changed");
        if(saved.revision()<=baseline.revision()||saved.revision()>MAX_REVISION)throw new InputRejected(503,"model_catalog_unavailable");
        var expected=materialize(baseline,bindings(baseline.kind(),saved.definition()),saved.revision());
        var encoded=CanonicalJson.encode(expected,131072);
        if(!encoded.sha256().equals(saved.sha256())||!encoded.sha256().equals(CanonicalJson.encode(saved.definition(),131072).sha256()))throw new InputRejected(503,"model_catalog_unavailable");
        return expected;
    }
    public static Map<String,String> bindings(String kind,Map<String,Object> definition){
        var result=new LinkedHashMap<String,String>();
        if("appearance".equals(kind)){
            Object selected=definition.get("active");if(!(selected instanceof String id))throw new IllegalArgumentException();result.put("appearance",id);
        }else if("processing".equals(kind)){
            var modules=object(definition.get("modules"));
            for(String operation:List.of("license_plate","text_ocr")){
                if(modules.get(operation)==null){result.put(operation,null);continue;}
                var module=object(modules.get(operation));
                if(!(module.get("enabled") instanceof Boolean enabled))throw new IllegalArgumentException();
                Object profile=module.get("profile");
                if(enabled&&!(profile instanceof String))throw new IllegalArgumentException();
                result.put(operation,enabled?(String)profile:null);
            }
        }else throw new IllegalArgumentException("Unknown model catalog");
        return result;
    }
    public static Map<String,Object> materialize(Baseline baseline,Map<String,String> bindings,long revision){
        if(bindings==null)throw new IllegalArgumentException();
        var value=decode(CanonicalJson.encode(baseline.definition(),131072).payload());
        if(!(value.get("profiles") instanceof List<?> profiles))throw new IllegalArgumentException();
        var available=new HashMap<String,Map<String,Object>>();
        for(Object profile:profiles){var definition=object(profile);available.put((String)definition.get("profile"),definition);}
        if("appearance".equals(baseline.kind())){
            if(!bindings.keySet().equals(Set.of("appearance"))||bindings.get("appearance")==null||!available.containsKey(bindings.get("appearance")))throw new InputRejected(422,"model_profile_unavailable");
            value.put("active",bindings.get("appearance"));
        }else if("processing".equals(baseline.kind())){
            if(!bindings.keySet().equals(Set.of("license_plate","text_ocr")))throw new IllegalArgumentException();
            var modules=object(value.get("modules"));
            for(String operation:List.of("license_plate","text_ocr")){
                String id=bindings.get(operation);var profile=available.get(id);
                if(id!=null&&(profile==null||!(profile.get("capabilities") instanceof List<?> capabilities)||!capabilities.contains(operation)))throw new InputRejected(422,"model_profile_unavailable");
                var selection=new LinkedHashMap<String,Object>();selection.put("enabled",id!=null);selection.put("profile",id);modules.put(operation,selection);
            }
        }else throw new IllegalArgumentException();
        value.put("revision",revision);return value;
    }
    @Transactional(timeout=10)
    public Map<String,Object> save(Baseline baseline,Command command,String actor){
        if(command==null||command.requestId()==null||command.expectedRevision()<0||command.expectedRevision()>=MAX_REVISION||!baseline.sha256().equals(command.baseSha256())||actor==null||actor.isBlank())throw new InputRejected(409,"model_catalog_source_changed");
        String sha=CanonicalJson.encode(Map.of("catalog",baseline.kind(),"expected_revision",command.expectedRevision(),"base_sha256",command.baseSha256(),"bindings",command.bindings()),8192).sha256();
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",command.requestId().toString());
        var receipts=db.queryForList("SELECT operation,actor_id,request_sha256,response::text AS response FROM application_command_receipts WHERE request_id=?",command.requestId());
        if(!receipts.isEmpty()){
            var receipt=receipts.getFirst();
            if(!"local_model_binding_update".equals(receipt.get("operation"))||!actor.equals(receipt.get("actor_id"))||!sha.equals(receipt.get("request_sha256")))throw new InputRejected(409,"command_request_conflict");
            return decode((String)receipt.get("response"));
        }
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","local-model-catalog:"+baseline.kind());
        var previous=read(baseline.kind());long current=previous==null?0:previous.revision();
        if(current!=command.expectedRevision())throw new InputRejected(409,"model_binding_revision_conflict");
        long next=Math.max(current,baseline.revision())+1;if(next>MAX_REVISION)throw new InputRejected(409,"model_binding_revision_conflict");
        var definition=materialize(baseline,command.bindings(),next);var encoded=CanonicalJson.encode(definition,131072);
        int changed=db.update("""
            INSERT INTO managed_model_catalogs(catalog_key,revision,base_sha256,definition_sha256,definition,updated_by)
            VALUES(?,?,?,?,?::jsonb,?) ON CONFLICT(catalog_key) DO UPDATE SET revision=EXCLUDED.revision,
            base_sha256=EXCLUDED.base_sha256,definition_sha256=EXCLUDED.definition_sha256,definition=EXCLUDED.definition,
            updated_by=EXCLUDED.updated_by,updated_at=clock_timestamp() WHERE managed_model_catalogs.revision=?
            """,baseline.kind(),next,baseline.sha256(),encoded.sha256(),encoded.payload(),actor,current);
        if(changed!=1)throw new InputRejected(409,"model_binding_revision_conflict");
        var response=Map.<String,Object>of("catalog",baseline.kind(),"revision",next,"sha256",encoded.sha256(),"base_sha256",baseline.sha256(),"bindings",command.bindings());
        String frozen=CanonicalJson.encode(response,16384).payload();
        db.update("INSERT INTO application_command_receipts(request_id,operation,actor_id,request_sha256,response) VALUES(?,'local_model_binding_update',?,?,?::jsonb)",command.requestId(),actor,sha,frozen);
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'local_model_binding_update',?,?)",actor,frozen,command.requestId());
        return response;
    }
}
