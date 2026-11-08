package cn.trial.videosearch.evidence;

import java.util.Locale;

public enum SpecialistCandidateState {
    READY,ACTIVE,WAITING_SOURCE,WAITING_CONFIGURATION,WAITING_BUDGET,WAITING_RESULT,WAITING_REVIEW,
    SUBMITTED,REUSED,SKIPPED,INVALID,EXPIRED,FAILED;
    public String key(){return name().toLowerCase(Locale.ROOT);}
}
