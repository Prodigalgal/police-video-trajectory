package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Advance already-fenced captures independently of worker restarts/retry order. */
@Component
public class CaptureDispatchScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(CaptureDispatchScheduler.class);
    private final TargetPipeline pipeline;
    private final boolean enabled;
    public CaptureDispatchScheduler(TargetPipeline pipeline,@Value("${VIDEO_CAPTURE_DISPATCHER_ENABLED:true}") boolean enabled) {
        this.pipeline=pipeline; this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${VIDEO_CAPTURE_INTERVAL_MS:2000}")
    public void dispatch() {
        if(!enabled) return;
        try { pipeline.dispatchWaiting(); }
        catch(RuntimeException error) { LOG.warn("capture_dispatch_deferred category={}",error.getClass().getSimpleName()); }
    }
}
