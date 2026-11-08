package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public final class ModuleQueuePolicy {
    private final AnalysisQueuePolicy retry;
    private final int pendingLimit;
    private final Map<String,Integer> limits;

    public ModuleQueuePolicy(@Value("${VIDEO_MODULE_QUEUE_LIMIT:1500}") int pendingLimit,
                             @Value("${VIDEO_MODULE_ACTIVE_LIMIT:3}") int activeLimit,
                             @Value("${VIDEO_MODULE_PERSON_LIMIT:1}") int personLimit,
                             @Value("${VIDEO_MODULE_VEHICLE_LIMIT:1}") int vehicleLimit,
                             @Value("${VIDEO_MODULE_SCENE_LIMIT:1}") int sceneLimit,
                             @Value("${VIDEO_MODULE_MAX_ATTEMPTS:5}") int maxAttempts,
                             @Value("${VIDEO_MODULE_RETRY_BASE_SECONDS:5}") int retryBase,
                             @Value("${VIDEO_MODULE_RETRY_MAX_SECONDS:300}") int retryMax) {
        if(pendingLimit<3 || pendingLimit>30000 || personLimit<1 || vehicleLimit<1 || sceneLimit<1 ||
                personLimit>activeLimit || vehicleLimit>activeLimit || sceneLimit>activeLimit)
            throw new IllegalArgumentException("invalid module queue limits");
        this.retry=new AnalysisQueuePolicy(activeLimit,maxAttempts,retryBase,retryMax);
        this.pendingLimit=pendingLimit;
        this.limits=Map.of("person_attributes",personLimit,"vehicle_attributes",vehicleLimit,"scene_context",sceneLimit);
    }
    public int pendingLimit(){return pendingLimit;}
    public int activeLimit(){return retry.activeLimit();}
    public int maxAttempts(){return retry.maxAttempts();}
    public int retryBaseSeconds(){return retry.retryBaseSeconds();}
    public int retryMaxSeconds(){return retry.retryMaxSeconds();}
    public int retryDelaySeconds(int attempts){return retry.retryDelaySeconds(attempts);}
    public int kindLimit(String kind){
        Integer limit=limits.get(kind);
        if(limit==null)throw new IllegalArgumentException("unsupported analysis module");
        return limit;
    }
    public Map<String,Object> snapshot(){return Map.of("pending_limit",pendingLimit,"active_limit",activeLimit(),
            "kind_limits",limits,"max_attempts",maxAttempts(),"retry_base_seconds",retryBaseSeconds(),"retry_max_seconds",retryMaxSeconds());}
}
