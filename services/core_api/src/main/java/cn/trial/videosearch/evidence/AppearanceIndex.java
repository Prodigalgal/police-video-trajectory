package cn.trial.videosearch.evidence;

import java.time.Instant;
import java.util.*;

/** Implementations must keep the same frozen feature space and authorized scope. */
public interface AppearanceIndex {
    record Filter(String type,List<String> cameras,Instant start,Instant end){}
    record Ranked(Map<String,Object> source,Map<String,Object> clock,String track,double score){}
    record Found(List<Ranked> candidates,int scanned,boolean truncated){}
    Found find(AppearanceCatalog.Profile profile,List<Double> vector,Filter filter,int candidates);
}
