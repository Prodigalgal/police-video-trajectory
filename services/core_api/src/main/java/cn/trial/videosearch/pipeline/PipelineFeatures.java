package cn.trial.videosearch.pipeline;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;

@Service
public class PipelineFeatures {
    public record Update(UUID requestId,Long expectedVersion,Boolean enabled,String plugin,Map<String,Object> parameters){}
    public record State(boolean enabled,boolean installed,String plugin,long version){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate db;private final boolean exportsInstalled;
    public PipelineFeatures(JdbcTemplate db,@Value("${VIDEO_EXPORTS_INSTALLED:${VIDEO_EXPORTS_ENABLED:false}}")boolean exportsInstalled){this.db=db;this.exportsInstalled=exportsInstalled;}
    public boolean installed(String feature){return PipelineFeatureRegistry.EXPORT.equals(feature)?exportsInstalled:PipelineFeatureRegistry.KEYS.contains(feature);}
    public State state(String feature,boolean lock){
        if(!PipelineFeatureRegistry.KEYS.contains(feature))throw new AdminService.Rejected(404,"pipeline_feature_unavailable");
        var rows=db.queryForList("SELECT enabled,plugin_key,parameters::text AS parameters,version FROM pipeline_features WHERE feature_key=?"+(lock?" FOR SHARE":""),feature);
        if(rows.size()!=1)throw new AdminService.Rejected(503,"pipeline_features_unavailable");var row=rows.getFirst();
        PipelineFeatureRegistry.validate(feature,(String)row.get("plugin_key"),object((String)row.get("parameters")));
        return new State(Boolean.TRUE.equals(row.get("enabled")),installed(feature),(String)row.get("plugin_key"),((Number)row.get("version")).longValue());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(String value){return JSON.readValue(value,Map.class);}
    private Map<String,Object> view(Map<String,Object> row){
        String key=(String)row.get("feature_key"),plugin=(String)row.get("plugin_key");
        var parameters=object((String)row.get("parameters"));PipelineFeatureRegistry.validate(key,plugin,parameters);
        var result=new LinkedHashMap<String,Object>(PipelineFeatureRegistry.definition(key));
        result.put("id",key);result.put("enabled",row.get("enabled"));result.put("plugin",plugin);result.put("parameters",parameters);
        result.put("installed",installed(key));
        result.put("version",row.get("version"));result.put("updated_at",((Timestamp)row.get("updated_at")).toInstant().toString());return result;
    }
    public Map<String,Object> snapshot(){
        var rows=db.queryForList("SELECT feature_key,enabled,plugin_key,parameters::text AS parameters,version,updated_at FROM pipeline_features WHERE feature_key IN (?,?) ORDER BY feature_key",PipelineFeatureRegistry.RECORDING,PipelineFeatureRegistry.EXPORT);
        if(rows.size()!=2)throw new AdminService.Rejected(503,"pipeline_features_unavailable");
        return Map.of("schema_version","pipeline-features-v1","lease_seconds",15,"features",rows.stream().map(this::view).toList());
    }
    @Transactional(timeout=10)
    public Map<String,Object> update(String feature,Update command,String actor){
        if(command==null||command.requestId()==null||command.expectedVersion()==null||command.expectedVersion()<1||command.enabled()==null)throw new IllegalArgumentException();
        PipelineFeatureRegistry.validate(feature,command.plugin(),command.parameters());
        if(command.enabled()&&!installed(feature))throw new AdminService.Rejected(409,"pipeline_feature_not_installed");
        String payload=CanonicalJson.encode(command.parameters(),1024).payload();
        String sha=CanonicalJson.encode(Map.of("feature",feature,"expected_version",command.expectedVersion(),"enabled",command.enabled(),"plugin",command.plugin(),"parameters",command.parameters()),4096).sha256();
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",command.requestId().toString());
        var prior=db.queryForList("SELECT operation,actor_id,request_sha256,response::text AS response FROM application_command_receipts WHERE request_id=?",command.requestId());
        if(!prior.isEmpty()){
            var saved=prior.getFirst();
            if(!"pipeline_feature_update".equals(saved.get("operation"))||!actor.equals(saved.get("actor_id"))||!sha.equals(saved.get("request_sha256")))throw new AdminService.Rejected(409,"command_request_conflict");
            return object((String)saved.get("response"));
        }
        int changed=db.update("UPDATE pipeline_features SET enabled=?,plugin_key=?,parameters=?::jsonb,version=version+1,updated_by=?,updated_at=clock_timestamp() WHERE feature_key=? AND version=? AND version<9007199254740991",
            command.enabled(),command.plugin(),payload,actor,feature,command.expectedVersion());
        if(changed!=1)throw new AdminService.Rejected(409,"pipeline_feature_revision_conflict");
        var response=snapshot();String frozen=CanonicalJson.encode(response,16384).payload();
        db.update("INSERT INTO application_command_receipts(request_id,operation,actor_id,request_sha256,response) VALUES(?,'pipeline_feature_update',?,?,?::jsonb)",command.requestId(),actor,sha,frozen);
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'pipeline_feature_update',?,?)",actor,frozen,command.requestId());
        return response;
    }
}
