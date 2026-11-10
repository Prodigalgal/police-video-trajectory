package cn.trial.videosearch.admin;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.http.SecureJsonClient;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Admin discovery never changes model bindings or holds a transaction over HTTP. */
@Service
public class ModelProviderCatalogs {
    private static final Set<String> ROOT=Set.of("schema_version","provider","configuration_sha256","state","checked_at","cached","models","catalog_sha256","diagnostics","inference_verified");
    private static final Set<String> MODEL=Set.of("id","name","input_media","declared_roles","allowed_roles","protocols","streaming","reasoning_levels","tools","analysis_reason","inference_verified");
    private final ModelProviders providers;private final JdbcTemplate db;private final SecureJsonClient gateway;private final String authorization;
    public ModelProviderCatalogs(ModelProviders providers,JdbcTemplate db,@Value("${VIDEO_GATEWAY_URL:}")String url,
            @Value("${VIDEO_GATEWAY_CA:}")String ca,@Value("${VIDEO_GATEWAY_TOKEN:}")String token){
        this.providers=providers;this.db=db;this.gateway=new SecureJsonClient(url,ca);authorization="Bearer "+token;
    }
    private ModelProviders.Provider provider(String id){
        if(id==null||!id.matches("[a-z][a-z0-9_-]{0,79}"))throw new InputRejected(422,"invalid_model_provider");
        var provider=providers.current().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->new InputRejected(404,"model_provider_unavailable"));
        if(!provider.activationAllowed())throw new InputRejected(409,"model_activation_forbidden");return provider;
    }
    static String fingerprint(ModelProviders.Provider provider){
        return CanonicalJson.encode(Map.of("id",provider.id(),"base_url",provider.baseUrl(),"credential_file",provider.credentialFile(),
            "protocols",provider.protocols().stream().sorted().toList(),"roles",provider.roles().stream().sorted().toList(),"activation_allowed",provider.activationAllowed()),4096).sha256();
    }
    public Map<String,Object> read(String id,boolean refresh,String actor){
        var selected=provider(id);String before=fingerprint(selected);Map<String,Object> result;
        try{result=gateway.get("/internal/v1/providers/"+id+"/models?refresh="+refresh,authorization,Duration.ofSeconds(28));}
        catch(SecureJsonClient.UpstreamFailure failure){throw new InputRejected(503,"model_catalog_unavailable");}
        if(!before.equals(fingerprint(provider(id))))throw new InputRejected(409,"model_catalog_configuration_changed");
        if(!before.equals(result.get("configuration_sha256")))throw new InputRejected(409,"model_catalog_configuration_changed");
        validate(result,selected);
        var audit=new LinkedHashMap<String,Object>();for(String key:List.of("provider","state","catalog_sha256","configuration_sha256","diagnostics","checked_at"))audit.put(key,result.get(key));
        audit.put("model_count",((List<?>)result.get("models")).size());audit.put("refresh",refresh);
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'models.catalog',?,?)",actor,CanonicalJson.encode(audit,8192).payload(),UUID.randomUUID());
        return result;
    }
    static void validate(Map<String,Object> value,ModelProviders.Provider provider){
        if(!value.keySet().equals(ROOT)||!"provider-model-catalog-v1".equals(value.get("schema_version"))||!provider.id().equals(value.get("provider"))
                ||!(value.get("state") instanceof String state)||!Set.of("ready","unavailable").contains(state)||!(value.get("cached") instanceof Boolean)||!Boolean.FALSE.equals(value.get("inference_verified"))
                ||!(value.get("models") instanceof List<?> models)||models.size()>512)throw invalid();
        try{Instant.parse((String)value.get("checked_at"));}catch(RuntimeException failure){throw invalid();}
        if(!CanonicalJson.encode(models,2*1024*1024).sha256().equals(value.get("catalog_sha256")))throw invalid();
        var ids=new HashSet<String>();
        for(Object raw:models){
            if(!(raw instanceof Map<?,?> model)||!model.keySet().equals(MODEL)||!(model.get("id") instanceof String id)||!id.matches("[A-Za-z0-9][A-Za-z0-9._:/+@() -]{0,149}")||!ids.add(id)
                ||!(model.get("name") instanceof String name)||name.isBlank()||name.length()>100||name.codePoints().anyMatch(Character::isISOControl)||!Boolean.FALSE.equals(model.get("inference_verified")))throw invalid();
            var declared=strings(model.get("declared_roles"),Set.of("text","vision"));var allowed=strings(model.get("allowed_roles"),provider.roles());
            var protocols=strings(model.get("protocols"),provider.protocols());var media=strings(model.get("input_media"),Set.of("text","image","audio","video","file"));
            var expectedRoles=new HashSet<String>();if(media.contains("text"))expectedRoles.add("text");if(media.contains("image"))expectedRoles.add("vision");
            strings(model.get("reasoning_levels"),Set.of("auto","none","minimal","low","medium","high","xhigh","max"));
            if(!declared.equals(expectedRoles)||!declared.containsAll(allowed)||!allowed.isEmpty()&&(!Boolean.TRUE.equals(model.get("streaming"))||protocols.isEmpty()||model.get("analysis_reason")!=null)
                ||model.get("streaming")!=null&&!(model.get("streaming") instanceof Boolean)||model.get("tools")!=null&&!(model.get("tools") instanceof Boolean)
                ||model.get("analysis_reason")!=null&&!"media_endpoint_required".equals(model.get("analysis_reason")))throw invalid();
        }
        Object raw=value.get("diagnostics");
        if("ready".equals(value.get("state"))?raw!=null:!models.isEmpty()||!(raw instanceof Map<?,?>))throw invalid();
        if(raw instanceof Map<?,?> diagnostics){
            if(!Set.of("category","upstream_status","upstream_request_id","upstream_error_code").containsAll(diagnostics.keySet())
                ||!(diagnostics.get("category") instanceof String category)||!Set.of("catalog_busy","credential_rejected","catalog_rate_limited","catalog_not_found","catalog_unavailable","configuration_changed","catalog_timeout","catalog_network_unavailable","catalog_invalid","credential_unavailable").contains(category))throw invalid();
            if(diagnostics.containsKey("upstream_status")&&(!(diagnostics.get("upstream_status") instanceof Number status)||status.intValue()<100||status.intValue()>599||status.doubleValue()!=status.intValue()))throw invalid();
            for(String key:List.of("upstream_request_id","upstream_error_code"))if(diagnostics.containsKey(key)&&(!(diagnostics.get(key) instanceof String text)||!text.matches(key.endsWith("id")?"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}":"[a-z_]{1,80}")))throw invalid();
        }
    }
    private static Set<String> strings(Object raw,Set<String> allowed){
        if(!(raw instanceof List<?> values)||values.size()>allowed.size())throw invalid();
        var result=new HashSet<String>();for(Object value:values)if(!(value instanceof String text)||!allowed.contains(text)||!result.add(text))throw invalid();return result;
    }
    private static InputRejected invalid(){return new InputRejected(503,"model_catalog_invalid");}
}
