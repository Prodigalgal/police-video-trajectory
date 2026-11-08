package cn.trial.videosearch.attributes;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Search suppression only; original model uncertainty strings remain intact. */
public final class UncertaintyTerms {
    public static final Set<String> ATTRIBUTES = Set.of("upper_color", "lower_color", "vehicle_color", "carrying", "transport_type", "visible_action");
    private static final Set<String> COLORS = Set.of("upper_color", "lower_color", "vehicle_color");
    private UncertaintyTerms() {}

    public static Set<String> suppressedAttributes(List<String> fields) {
        var blocked = new HashSet<String>();
        if (fields == null) return Set.of();
        for (String original : fields) {
            if (original == null) continue;
            String field = original.strip().toLowerCase(Locale.ROOT);
            if (field.startsWith("attributes.")) field = field.substring("attributes.".length());
            // An uncertain element makes the unstructured carrying list
            // unsuitable for a positive hard filter as a whole.
            if (field.startsWith("carrying[") || field.startsWith("carrying.")) field = "carrying";
            switch (field) {
                case "upper_color", "upper_clothing_color", "uppercolor", "上衣颜色", "上装颜色", "上身颜色" -> blocked.add("upper_color");
                case "lower_color", "lower_clothing_color", "lowercolor", "下装颜色", "裤子颜色", "下身颜色" -> blocked.add("lower_color");
                case "vehicle_color", "vehiclecolor", "车身颜色", "车辆颜色" -> blocked.add("vehicle_color");
                case "clothing_color", "clothingcolor", "衣服颜色", "衣着颜色", "服装颜色" -> blocked.addAll(Set.of("upper_color", "lower_color"));
                case "color", "colour", "颜色" -> blocked.addAll(COLORS);
                case "carrying", "carrying_items", "携带物", "携带物品", "携物" -> blocked.add("carrying");
                case "transport_type", "transporttype", "vehicle_type", "车型", "交通工具", "交通工具类型" -> blocked.add("transport_type");
                case "visible_action", "visibleaction", "可见动作", "动作" -> blocked.add("visible_action");
                case "attributes", "all_attributes" -> blocked.addAll(ATTRIBUTES);
                default -> { /* unrelated uncertainty cannot invent an attribute value */ }
            }
        }
        return Set.copyOf(blocked);
    }
}
