package cn.trial.videosearch.search;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Treat gateway output as untrusted even after its own schema validation. */
public final class SearchPlanParser {
    private static final Set<String> PLAN_FIELDS = Set.of("schema_version", "target_type", "time_range", "location_text", "camera_ids", "attributes", "text_terms", "clarifications");
    private static final Set<String> ATTR_FIELDS = Set.of("upper_color", "lower_color", "clothing_color", "vehicle_color", "carrying", "transport_type", "visible_action");
    private SearchPlanParser() {}

    /** The versioned wire document is shared by paging and refinement signatures. */
    public static Map<String,Object> document(SearchPlan plan) {
        var result=new LinkedHashMap<String,Object>();result.put("schema_version","1.0");result.put("target_type",plan.targetType());
        var time=new LinkedHashMap<String,Object>();time.put("start",plan.start()==null?null:plan.start().toString());time.put("end",plan.end()==null?null:plan.end().toString());
        result.put("time_range",time);result.put("location_text",plan.locationText());result.put("camera_ids",plan.cameraIds());
        var attrs=new LinkedHashMap<String,Object>();var attributes=plan.attributes();
        attrs.put("upper_color",attributes==null?null:attributes.upperColor());attrs.put("lower_color",attributes==null?null:attributes.lowerColor());
        attrs.put("clothing_color",attributes==null?null:attributes.clothingColor());attrs.put("vehicle_color",attributes==null?null:attributes.vehicleColor());
        attrs.put("carrying",attributes==null?List.of():attributes.carrying());attrs.put("transport_type",attributes==null?null:attributes.transportType());attrs.put("visible_action",attributes==null?null:attributes.visibleAction());
        result.put("attributes",attrs);result.put("text_terms",plan.textTerms());result.put("clarifications",plan.clarifications());return result;
    }

    public static SearchPlan parse(Object value) {
        var root = object(value, PLAN_FIELDS);
        if (!"1.0".equals(root.get("schema_version"))) throw invalid();
        String target = text(root.get("target_type"), false, 20);
        if (!Set.of("person", "vehicle", "unknown").contains(target)) throw invalid();
        String location = text(root.get("location_text"), true, 200);
        Instant start = null;
        Instant end = null;
        if (root.get("time_range") != null) {
            var range = object(root.get("time_range"), Set.of("start", "end"));
            start = time(range.get("start"));
            end = time(range.get("end"));
            if (start != null && end != null && !start.isBefore(end)) throw invalid();
        }
        var attrs = object(root.get("attributes"), ATTR_FIELDS);
        var attributes = new SearchPlan.Attributes(text(attrs.get("upper_color"), true, 80), text(attrs.get("lower_color"), true, 80),
                text(attrs.get("clothing_color"), true, 80), strings(attrs.get("carrying"), 12, 100),
                text(attrs.get("transport_type"), true, 80), text(attrs.get("visible_action"), true, 80),text(attrs.get("vehicle_color"),true,80));
        if ("person".equals(target) && attributes.vehicleColor()!=null) throw invalid();
        return new SearchPlan(target, start, end, location, strings(root.get("camera_ids"), 50, 200),
                attributes, strings(root.get("text_terms"), 20, 200), strings(root.get("clarifications"), 10, 200));
    }

    private static Map<?, ?> object(Object value, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map) || !allowed.containsAll(map.keySet())) throw invalid();
        return map;
    }

    private static String text(Object value, boolean optional, int max) {
        if (optional && value == null) return null;
        if (!(value instanceof String s) || s.isBlank() || s.length() > max) throw invalid();
        return s.strip();
    }

    private static List<String> strings(Object value, int maxItems, int maxLength) {
        if (!(value instanceof List<?> list) || list.size() > maxItems) throw invalid();
        var result = new ArrayList<String>();
        for (Object item : list) result.add(text(item, false, maxLength));
        return List.copyOf(result);
    }

    private static Instant time(Object value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(text(value, false, 100)).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid gateway search plan"); }
}
