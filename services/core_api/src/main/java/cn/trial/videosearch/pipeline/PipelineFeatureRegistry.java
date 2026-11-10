package cn.trial.videosearch.pipeline;

import cn.trial.videosearch.admin.AdminService;
import java.util.*;

/** Installed adapters and defaults, separate from operator-owned enabled state. */
public final class PipelineFeatureRegistry {
    public static final String RECORDING="frame_recording";
    public static final String EXPORT="evidence_export";
    public static final Set<String> KEYS=Set.of(RECORDING,EXPORT);
    private PipelineFeatureRegistry(){}
    public static void validate(String feature,String plugin,Map<String,Object> parameters){
        if(!KEYS.contains(feature))throw new AdminService.Rejected(404,"pipeline_feature_unavailable");
        if(parameters==null)throw new IllegalArgumentException("Feature parameters required");
        if(EXPORT.equals(feature)){
            if(!"bound_video_package_v1".equals(plugin))throw new AdminService.Rejected(422,"pipeline_plugin_unavailable");
            if(!parameters.isEmpty())throw new IllegalArgumentException("Video export takes no recording parameters");return;
        }
        if("short_segment_v1".equals(plugin)){
            if(!parameters.isEmpty())throw new IllegalArgumentException("Short recording takes no context parameters");
        }else if("context_window_v1".equals(plugin)){
            Object seconds=parameters.get("seconds");
            if(!parameters.keySet().equals(Set.of("seconds"))||!(seconds instanceof Integer||seconds instanceof Long)
                ||!Set.of(3L,5L,10L).contains(((Number)seconds).longValue()))throw new IllegalArgumentException("Invalid context window");
        }else throw new AdminService.Rejected(422,"pipeline_plugin_unavailable");
    }
    public static Map<String,Object> definition(String feature){
        if(!KEYS.contains(feature))throw new AdminService.Rejected(404,"pipeline_feature_unavailable");
        if(EXPORT.equals(feature))return Map.of("label","视频证据包","default_enabled",false,"storage_impact","high",
            "plugins",List.of(Map.of("id","bound_video_package_v1","label","已绑定录像打包","minimum_quota_mib",516)),"apply_scope","new_exports");
        return Map.of("label","有效帧录像","default_enabled",false,"storage_impact","high",
            "plugins",List.of(Map.of("id","short_segment_v1","label","同会话短段","minimum_quota_mib",32),
                Map.of("id","context_window_v1","label","前后上下文","minimum_quota_mib",256)),
            "context_seconds",List.of(3,5,10),"apply_scope","new_windows");
    }
}
