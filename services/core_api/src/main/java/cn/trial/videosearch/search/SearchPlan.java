package cn.trial.videosearch.search;

import cn.trial.videosearch.attributes.ColorTerms;

import java.time.Instant;
import java.util.List;

/** Internal, validated interpretation of a user's request, never arbitrary DSL. */
public record SearchPlan(
        String targetType,
        Instant start,
        Instant end,
        String locationText,
        List<String> cameraIds,
        Attributes attributes,
        List<String> textTerms,
        List<String> clarifications) {

    public record Attributes(String upperColor, String lowerColor, String clothingColor,
                             List<String> carrying, String transportType, String visibleAction,String vehicleColor) {
        public Attributes {
            upperColor=ColorTerms.canonical(upperColor);
            lowerColor=ColorTerms.canonical(lowerColor);
            clothingColor=ColorTerms.canonical(clothingColor);
            vehicleColor=ColorTerms.canonical(vehicleColor);
        }
        public Attributes(String upperColor,String lowerColor,String clothingColor,List<String> carrying,String transportType,String visibleAction) {
            this(upperColor,lowerColor,clothingColor,carrying,transportType,visibleAction,null);
        }
    }
}
