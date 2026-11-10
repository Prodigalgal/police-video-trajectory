package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.pipeline.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Installation, operator intent and recently observed consumers are independent facts. */
@Service
public class ExportRuntime {
    public static final String CONTRACT="evidence-export-v1",LARGE_NATIVE="evidence-native-128-v1";
    private static final Set<String> CONTRACTS=Set.of(CONTRACT,LARGE_NATIVE);
    public record Heartbeat(String workerId,List<String> contracts,long quotaBytes,String version){}
    private final JdbcTemplate db;private final PipelineFeatures features;
    public ExportRuntime(JdbcTemplate db,PipelineFeatures features){this.db=db;this.features=features;}
    public void requireInstalled(){if(!features.installed(PipelineFeatureRegistry.EXPORT))throw new InputRejected(404,"exports_disabled");}
    public boolean producing(boolean lock){return features.installed(PipelineFeatureRegistry.EXPORT)&&features.state(PipelineFeatureRegistry.EXPORT,lock).enabled();}
    public void requireProducing(boolean lock){requireInstalled();if(!producing(lock))throw new InputRejected(409,"export_module_disabled");}
    public void requireConsumer(boolean largeNative){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM evidence_export_consumers WHERE seen_at>clock_timestamp()-interval '75 seconds' AND jsonb_exists(contracts,?))",Boolean.class,largeNative?LARGE_NATIVE:CONTRACT)))throw new InputRejected(409,"export_consumer_unavailable");
    }
    @Transactional(timeout=10)
    public Map<String,Object> heartbeat(Heartbeat body){
        requireInstalled();
        if(body==null||body.workerId()==null||!body.workerId().matches("[A-Za-z0-9_.-]{1,100}")||body.contracts()==null||body.contracts().isEmpty()
            ||body.contracts().size()>CONTRACTS.size()||body.contracts().stream().anyMatch(value->value==null||!CONTRACTS.contains(value))
            ||new HashSet<>(body.contracts()).size()!=body.contracts().size()||!body.contracts().contains(CONTRACT)
            ||body.quotaBytes()<ExportPlans.MAX_ARTIFACT||body.quotaBytes()>16L*1024*1024*1024||body.version()==null||body.version().length()>40||!body.version().matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))throw new IllegalArgumentException("Invalid export consumer");
        db.execute("SELECT pg_advisory_xact_lock(918273696)");
        db.update("DELETE FROM evidence_export_consumers WHERE seen_at<clock_timestamp()-interval '1 hour'");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM evidence_export_consumers WHERE worker_id=?)",Boolean.class,body.workerId()))
            &&db.queryForObject("SELECT count(*) FROM evidence_export_consumers",Long.class)>=16)throw new InputRejected(429,"export_consumer_registry_full");
        db.update("INSERT INTO evidence_export_consumers(worker_id,contracts,quota_bytes,version) VALUES(?,?::jsonb,?,?) ON CONFLICT(worker_id) DO UPDATE SET contracts=excluded.contracts,quota_bytes=excluded.quota_bytes,version=excluded.version,seen_at=clock_timestamp()",
            body.workerId(),cn.trial.videosearch.support.CanonicalJson.encode(body.contracts(),512).payload(),body.quotaBytes(),body.version());return Map.of("accepted",true);
    }
    public Map<String,Object> status(){
        boolean installed=features.installed(PipelineFeatureRegistry.EXPORT);var result=new LinkedHashMap<String,Object>();
        result.put("schema_version","export-module-v1");result.put("installed",installed);
        var policy=features.state(PipelineFeatureRegistry.EXPORT,false);
        var rows=installed?db.queryForList("SELECT version FROM evidence_export_consumers WHERE seen_at>clock_timestamp()-interval '75 seconds' AND jsonb_exists(contracts,'evidence-export-v1') ORDER BY seen_at DESC,worker_id LIMIT 16"):List.<Map<String,Object>>of();
        result.put("enabled",policy.enabled());result.put("version",policy.version());result.put("plugin",policy.plugin());
        result.put("online",!rows.isEmpty());result.put("consumer_count",rows.size());result.put("consumer_versions",rows.stream().map(row->(String)row.get("version")).distinct().sorted().toList());result.put("can_create",installed&&policy.enabled()&&!rows.isEmpty());return result;
    }
    public static boolean needsLargeNative(Map<String,Object> plan){return ExportPlans.rows(plan.get("assets")).stream().anyMatch(asset->"native".equals(asset.get("kind"))&&((Number)asset.get("bytes")).longValue()>16L*1024*1024);}
}
