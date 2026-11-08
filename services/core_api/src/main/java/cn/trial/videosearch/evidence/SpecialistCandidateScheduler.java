package cn.trial.videosearch.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SpecialistCandidateScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(SpecialistCandidateScheduler.class);
    private final SpecialistCandidateProducer producer;
    public SpecialistCandidateScheduler(SpecialistCandidateProducer producer){this.producer=producer;}
    @Scheduled(fixedDelayString="${VIDEO_SPECIALIST_CANDIDATE_POLL_MS:2000}")
    public void tick(){
        try{producer.tick();}
        catch(RuntimeException unavailable){LOG.warn("Specialist candidate tick interrupted; leases remain recoverable ({})",unavailable.getClass().getSimpleName());}
    }
}
