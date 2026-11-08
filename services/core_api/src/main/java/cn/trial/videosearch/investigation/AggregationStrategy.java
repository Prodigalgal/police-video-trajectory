package cn.trial.videosearch.investigation;
import java.util.*;
public interface AggregationStrategy {
    String id();
    List<Map<String,Object>> aggregate(ObservationPoint anchor,List<ObservationPoint> observations,int maximumGapMs);
}
