package cn.trial.videosearch.index;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxIndexScheduler {
    private final OutboxIndexTransaction index;
    private final boolean enabled;

    public OutboxIndexScheduler(OutboxIndexTransaction index, @Value("${VIDEO_INDEXER_ENABLED:false}") boolean enabled) {
        this.index = index;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${VIDEO_INDEX_INTERVAL_MS:3000}")
    public void drain() {
        if (!enabled) return;
        for (int i = 0; i < 20 && index.processOne(); i++) { /* one transaction per event */ }
    }
}
