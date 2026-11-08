package cn.trial.videosearch.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record SpecialistCandidatePolicy(boolean enabled,int pendingLimit,int batchSize,int activeLimit,int leaseSeconds,int maxFailures,int maxAgeSeconds) {
    public SpecialistCandidatePolicy(
        @Value("${VIDEO_SPECIALIST_CANDIDATES_ENABLED:false}") boolean enabled,
        @Value("${VIDEO_SPECIALIST_CANDIDATE_PENDING_LIMIT:500}") int pendingLimit,
        @Value("${VIDEO_SPECIALIST_CANDIDATE_BATCH:20}") int batchSize,
        @Value("${VIDEO_SPECIALIST_CANDIDATE_ACTIVE_LIMIT:1}") int activeLimit,
        @Value("${VIDEO_SPECIALIST_CANDIDATE_LEASE_SECONDS:120}") int leaseSeconds,
        @Value("${VIDEO_SPECIALIST_CANDIDATE_MAX_FAILURES:5}") int maxFailures,
        @Value("${VIDEO_SPECIALIST_CANDIDATE_MAX_AGE_SECONDS:900}") int maxAgeSeconds) {
        if(pendingLimit<1||pendingLimit>5000||batchSize<1||batchSize>50||batchSize>pendingLimit||activeLimit<1||activeLimit>4
            ||leaseSeconds<30||leaseSeconds>600||maxFailures<1||maxFailures>10||maxAgeSeconds<60||maxAgeSeconds>86400)
            throw new IllegalArgumentException("Invalid specialist candidate policy");
        this.enabled=enabled;this.pendingLimit=pendingLimit;this.batchSize=batchSize;this.activeLimit=activeLimit;
        this.leaseSeconds=leaseSeconds;this.maxFailures=maxFailures;this.maxAgeSeconds=maxAgeSeconds;
    }
    int retrySeconds(int failures){return Math.min(300,5*(1<<Math.min(6,Math.max(0,failures-1))));}
}
