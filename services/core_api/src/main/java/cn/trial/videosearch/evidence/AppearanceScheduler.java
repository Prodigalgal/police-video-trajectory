package cn.trial.videosearch.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppearanceScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(AppearanceScheduler.class);
    private final AppearanceJobs jobs;
    public AppearanceScheduler(AppearanceJobs jobs){this.jobs=jobs;}
    @Scheduled(fixedDelayString="${VIDEO_APPEARANCE_SCAN_MS:30000}")
    public void produce(){try{jobs.scheduledProduce();}catch(Exception failure){LOG.warn("Appearance production deferred",failure);}}
}
