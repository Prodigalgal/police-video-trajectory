package cn.trial.videosearch.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ReviewedPlateScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ReviewedPlateScheduler.class);
    private final ReviewedPlatePublisher publisher;
    public ReviewedPlateScheduler(ReviewedPlatePublisher publisher){this.publisher=publisher;}
    @Scheduled(scheduler="reviewedPlateScheduler",fixedDelayString="${VIDEO_REVIEWED_PLATE_POLL_MS:2000}")
    public void tick(){try{publisher.processOne();}catch(RuntimeException unavailable){LOG.warn("Reviewed plate publication deferred ({})",unavailable.getClass().getSimpleName());}}
    @Configuration
    static class Scheduling {
        @Bean("taskScheduler")
        ThreadPoolTaskScheduler defaults(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("core-scheduled-");scheduler.setDaemon(true);return scheduler;}
        @Bean("reviewedPlateScheduler")
        ThreadPoolTaskScheduler scheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("reviewed-plate-");scheduler.setDaemon(true);scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setRemoveOnCancelPolicy(true);return scheduler;}
    }
}
