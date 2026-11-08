package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Shared admission and retry policy; every consumer instance uses the database quota. */
@Component
public final class AnalysisQueuePolicy {
    private final int activeLimit;
    private final int maxAttempts;
    private final int retryBaseSeconds;
    private final int retryMaxSeconds;

    public AnalysisQueuePolicy(@Value("${VIDEO_ANALYSIS_ACTIVE_LIMIT:2}") int activeLimit,
                               @Value("${VIDEO_ANALYSIS_MAX_ATTEMPTS:5}") int maxAttempts,
                               @Value("${VIDEO_ANALYSIS_RETRY_BASE_SECONDS:5}") int retryBaseSeconds,
                               @Value("${VIDEO_ANALYSIS_RETRY_MAX_SECONDS:300}") int retryMaxSeconds) {
        if (activeLimit < 1 || activeLimit > 64 || maxAttempts < 1 || maxAttempts > 20 ||
                retryBaseSeconds < 1 || retryBaseSeconds > 300 || retryMaxSeconds < retryBaseSeconds || retryMaxSeconds > 3600)
            throw new IllegalArgumentException("invalid analysis queue policy");
        this.activeLimit = activeLimit;
        this.maxAttempts = maxAttempts;
        this.retryBaseSeconds = retryBaseSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
    }

    public int activeLimit() { return activeLimit; }
    public int maxAttempts() { return maxAttempts; }
    public int retryBaseSeconds() { return retryBaseSeconds; }
    public int retryMaxSeconds() { return retryMaxSeconds; }

    public int retryDelaySeconds(int attempts) {
        if (attempts < 1) throw new IllegalArgumentException("invalid analysis attempt");
        return (int) Math.min(retryMaxSeconds, (long) retryBaseSeconds << Math.min(attempts - 1, 20));
    }

    public Map<String, Integer> snapshot(int pendingLimit) {
        return Map.of("pending_limit", pendingLimit, "active_limit", Math.min(pendingLimit, activeLimit),
                "max_attempts", maxAttempts, "retry_base_seconds", retryBaseSeconds, "retry_max_seconds", retryMaxSeconds);
    }
}
