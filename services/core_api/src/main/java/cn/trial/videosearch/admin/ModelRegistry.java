package cn.trial.videosearch.admin;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.sql.Timestamp;

/** Immutable model revisions; binding changes never rewrite an in-flight request. */
@Service
public class ModelRegistry {
    public record Definition(String display_name,String provider,String model,String protocol,List<String> roles,String image_transport,String output_mode,Boolean json_mode,String reasoning_effort){}
    public record Save(UUID request_id,int expected_version,Definition definition){}
    public record Selection(UUID profile_id,int version){}
    public record Bind(UUID request_id,long expected_revision,Map<String,Selection> bindings){}
    public record Receipt(String consumer_id,long revision,String sha256){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> ROLES=Set.of("text","vision");
    private final JdbcTemplate db;
    private final UserService users;
    private final ModelProviders providers;
    public ModelRegistry(JdbcTemplate db,UserService users,ModelProviders providers){this.db=db;this.users=users;this.providers=providers;}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value){return (Map<String,Object>)JSON.readValue(value.toString(),Map.class);}
    private static String instant(Object value){return value instanceof Timestamp timestamp?timestamp.toInstant().toString():value.toString();}
    private static String encoded(Object value){return CanonicalJson.encode(value,262144).payload();}
    static Map<String,Object> definition(Definition body,ModelProviders.Provider provider){
        if(body==null||body.roles()==null||body.json_mode()==null||body.provider()==null||body.protocol()==null||body.image_transport()==null||body.output_mode()==null||body.reasoning_effort()==null)throw new IllegalArgumentException();
        var roles=ModelProviders.strings(body.roles(),provider.roles());
        String label=ModelProviders.text(body.display_name(),100),model=ModelProviders.text(body.model(),150);
        if(!provider.id().equals(body.provider())||!provider.protocols().contains(body.protocol())||!Set.of("inline","file").contains(body.image_transport())||!Set.of("text","tool").contains(body.output_mode())||body.json_mode()&&"tool".equals(body.output_mode())||!Set.of("","none","minimal","low","medium","high","xhigh","max","auto").contains(body.reasoning_effort()))throw new IllegalArgumentException();
        var value=new LinkedHashMap<String,Object>();
        value.put("display_name",label);value.put("provider",provider.id());value.put("model",model);value.put("protocol",body.protocol());value.put("roles",new TreeSet<>(roles).stream().toList());
        value.put("image_transport",body.image_transport());value.put("output_mode",body.output_mode());value.put("json_mode",body.json_mode());value.put("reasoning_effort",body.reasoning_effort());
        value.put("base_url",provider.baseUrl());value.put("credential_file",provider.credentialFile());return value;
    }
    private ModelProviders.Provider provider(String id,List<ModelProviders.Provider> available){return available.stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->new AdminService.Rejected(409,"model_provider_unavailable"));}
    private static Map<String,Object> publicDefinition(Map<String,Object> definition){var copy=new LinkedHashMap<>(definition);copy.remove("credential_file");return copy;}
    private void lock(){db.queryForObject("SELECT revision FROM model_bindings WHERE singleton FOR UPDATE",Long.class);}
    private Map<String,Object> prior(UUID request,String sha){
        if(request==null)throw new IllegalArgumentException();
        var rows=db.queryForList("SELECT request_sha256,response::text FROM model_changes WHERE request_id=?",request);
        if(rows.isEmpty())return null;if(!sha.equals(rows.getFirst().get("request_sha256")))throw new AdminService.Rejected(409,"model_request_conflict");return object(rows.getFirst().get("response"));
    }
    private Map<String,Object> remember(UUID request,String sha,Map<String,Object> response,String actor,String action){
        db.update("INSERT INTO model_changes(request_id,request_sha256,response) VALUES(?,?,?::jsonb)",request,sha,encoded(response));
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,action,"配置修订已保存",request);return response;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(){
        var available=providers.current();var profiles=new ArrayList<Map<String,Object>>();
        for(var row:db.queryForList("SELECT profile_id,version,definition::text,created_at FROM model_profiles ORDER BY created_at DESC,profile_id,version DESC LIMIT 513")){
            var value=publicDefinition(object(row.get("definition")));value.put("profile_id",row.get("profile_id").toString());value.put("version",row.get("version"));value.put("created_at",instant(row.get("created_at")));profiles.add(value);
        }
        var result=new LinkedHashMap<String,Object>();result.put("schema_version","managed-models-v1");result.put("providers",available.stream().map(ModelProviders.Provider::publicView).toList());result.put("profiles",profiles);result.put("state",publicState(state()));
        result.put("consumers",db.queryForList("SELECT consumer_id,revision,binding_sha256,seen_at FROM model_consumers ORDER BY seen_at DESC LIMIT 16").stream().map(row->Map.of("consumer_id",row.get("consumer_id"),"revision",row.get("revision"),"sha256",row.get("binding_sha256"),"seen_at",instant(row.get("seen_at")))).toList());return result;
    }
    @Transactional
    public Map<String,Object> save(UUID id,Save body,String actor){
        if(id==null||body==null||body.definition()==null||body.expected_version()<0||body.expected_version()>9999)throw new IllegalArgumentException();
        var definition=definition(body.definition(),provider(body.definition().provider(),providers.current()));
        String sha=CanonicalJson.encode(Map.of("action","save","actor",actor,"id",id.toString(),"expected",body.expected_version(),"definition",definition),16384).sha256();
        lock();users.requireAdmin(actor);var prior=prior(body.request_id(),sha);if(prior!=null)return prior;
        Integer latest=db.queryForObject("SELECT coalesce(max(version),0) FROM model_profiles WHERE profile_id=?",Integer.class,id);
        if(latest==null||latest!=body.expected_version())throw new AdminService.Rejected(409,"model_profile_conflict");
        if(db.queryForObject("SELECT count(*) FROM model_profiles",Long.class)>=512||latest==0&&db.queryForObject("SELECT count(DISTINCT profile_id) FROM model_profiles",Long.class)>=64)throw new AdminService.Rejected(409,"model_registry_full");
        db.update("INSERT INTO model_profiles(profile_id,version,definition,created_by) VALUES(?,?,?::jsonb,?)",id,latest+1,encoded(definition),actor);
        return remember(body.request_id(),sha,Map.of("profile_id",id.toString(),"version",latest+1),actor,"model.save");
    }
    private Map<String,Object> state(){return db.queryForMap("SELECT revision,bindings::text,updated_at FROM model_bindings WHERE singleton");}
    private static Map<String,Object> publicState(Map<String,Object> row){
        var bindings=new LinkedHashMap<String,Object>();object(row.get("bindings")).forEach((role,raw)->{
            if(raw instanceof Map<?,?> value)bindings.put(role,Map.of("profile_id",value.get("profile_id"),"version",value.get("version")));else bindings.put(role,null);
        });
        return Map.of("revision",row.get("revision"),"bindings",bindings,"updated_at",instant(row.get("updated_at")));
    }
    @Transactional
    public Map<String,Object> bind(Bind body,String actor){
        if(body==null||body.expected_revision()<0||body.bindings()==null||!body.bindings().keySet().equals(ROLES))throw new IllegalArgumentException();
        var selections=new LinkedHashMap<String,Object>();
        for(String role:new TreeSet<>(ROLES)){var selection=body.bindings().get(role);if(selection!=null&&(selection.profile_id()==null||selection.version()<1))throw new IllegalArgumentException();selections.put(role,selection==null?null:Map.of("profile_id",selection.profile_id().toString(),"version",selection.version()));}
        String sha=CanonicalJson.encode(Map.of("action","bind","actor",actor,"expected",body.expected_revision(),"bindings",selections),16384).sha256();
        var available=providers.current();lock();users.requireAdmin(actor);var prior=prior(body.request_id(),sha);if(prior!=null)return prior;
        var current=state();if(((Number)current.get("revision")).longValue()!=body.expected_revision())throw new AdminService.Rejected(409,"model_binding_conflict");
        var bindings=new LinkedHashMap<String,Object>();
        for(String role:new TreeSet<>(ROLES)){
            var selection=body.bindings().get(role);if(selection==null){bindings.put(role,null);continue;}
            var rows=db.queryForList("SELECT definition::text FROM model_profiles WHERE profile_id=? AND version=?",selection.profile_id(),selection.version());
            if(rows.size()!=1)throw new AdminService.Rejected(404,"model_profile_unavailable");
            var definition=object(rows.getFirst().get("definition"));var provider=provider((String)definition.get("provider"),available);
            if(!provider.activationAllowed()||!((List<?>)definition.get("roles")).contains(role)||!provider.roles().contains(role)||!provider.protocols().contains(definition.get("protocol"))||!provider.baseUrl().equals(definition.get("base_url"))||!provider.credentialFile().equals(definition.get("credential_file")))throw new AdminService.Rejected(409,"model_activation_forbidden");
            bindings.put(role,Map.of("profile_id",selection.profile_id().toString(),"version",selection.version(),"definition",definition));
        }
        db.update("UPDATE model_bindings SET revision=revision+1,bindings=?::jsonb,updated_at=clock_timestamp() WHERE singleton",encoded(bindings));
        return remember(body.request_id(),sha,Map.of("state",publicState(state())),actor,"model.bind");
    }
    public Map<String,Object> runtime(){
        var row=state();long revision=((Number)row.get("revision")).longValue();var bindings=object(row.get("bindings"));
        var available=providers.current();
        for(var entry:bindings.entrySet())if(entry.getValue() instanceof Map<?,?> binding){
            var definition=(Map<?,?>)binding.get("definition");var provider=provider((String)definition.get("provider"),available);
            if(!provider.activationAllowed()||!provider.roles().contains(entry.getKey())||!provider.protocols().contains(definition.get("protocol"))||!provider.baseUrl().equals(definition.get("base_url"))||!provider.credentialFile().equals(definition.get("credential_file")))throw new AdminService.Rejected(503,"model_provider_unavailable");
        }
        var result=new LinkedHashMap<String,Object>();result.put("schema_version","model-bindings-v1");result.put("revision",revision);result.put("managed",revision>0);result.put("bindings",bindings);result.put("sha256",CanonicalJson.encode(Map.of("revision",revision,"bindings",bindings),131072).sha256());return result;
    }
    @Transactional
    public Map<String,Object> acknowledge(Receipt receipt){
        if(receipt==null||receipt.consumer_id()==null||!receipt.consumer_id().matches("[a-zA-Z0-9_.-]{1,80}")||receipt.revision()<0||receipt.sha256()==null||!receipt.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
        lock();var current=runtime();if(!Objects.equals(current.get("revision"),receipt.revision())||!current.get("sha256").equals(receipt.sha256()))throw new AdminService.Rejected(409,"model_binding_conflict");
        if(db.queryForObject("SELECT count(*) FROM model_consumers WHERE consumer_id=?",Long.class,receipt.consumer_id())==0&&db.queryForObject("SELECT count(*) FROM model_consumers",Long.class)>=16)throw new AdminService.Rejected(409,"model_registry_full");
        db.update("INSERT INTO model_consumers(consumer_id,revision,binding_sha256) VALUES(?,?,?) ON CONFLICT(consumer_id) DO UPDATE SET revision=excluded.revision,binding_sha256=excluded.binding_sha256,seen_at=clock_timestamp()",receipt.consumer_id(),receipt.revision(),receipt.sha256());return Map.of("accepted",true);
    }
}
