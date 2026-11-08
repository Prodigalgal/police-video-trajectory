package cn.trial.videosearch.investigation;
import java.util.*;
public interface AssociationStrategy {
    String id();
    Optional<Map<String,Object>> suggest(ObservationPoint anchor,ObservationPoint candidate);
}
