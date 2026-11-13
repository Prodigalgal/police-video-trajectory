package cn.trial.videosearch.pipeline;

import cn.trial.videosearch.admin.AdminService;
import java.util.*;

/** Installed adapters and defaults, separate from operator-owned enabled state. */
public final class PipelineFeatureRegistry {
    public static final String EXPORT="evidence_export";
    public static final Set<String> KEYS=Set.of(EXPORT);
    private PipelineFeatureRegistry(){}
    public static void validate(String feature,String plugin,Map<String,Object> parameters){
        if(!KEYS.contains(feature))throw new AdminService.Rejected(404,"pipeline_feature_unavailable");
        if(parameters==null)throw new IllegalArgumentException("Feature parameters required");
        if(!"bound_video_package_v1".equals(plugin))throw new AdminService.Rejected(422,"pipeline_plugin_unavailable");
        if(!parameters.isEmpty())throw new IllegalArgumentException("Video export takes no recording parameters");
    }
    public static Map<String,Object> definition(String feature){
        if(!KEYS.contains(feature))throw new AdminService.Rejected(404,"pipeline_feature_unavailable");
        return Map.of("label","视频证据包","default_enabled",false,"storage_impact","high",
            "plugins",List.of(Map.of("id","bound_video_package_v1","label","已绑定录像打包","minimum_quota_mib",516)),"apply_scope","new_exports");
    }
}
