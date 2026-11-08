package cn.trial.videosearch.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Scope filters plus scored full-text concepts over one object's LLM semantics. */
public final class SearchQueryCompiler {
    private SearchQueryCompiler() {}

    public static Map<String, Object> compile(SearchPlan plan, Set<String> authorizedCameras,
                                               Set<String> resolvedLocationCameras, int limit) {
        if (plan == null || authorizedCameras == null || authorizedCameras.isEmpty()) {
            throw new IllegalArgumentException("no authorized cameras");
        }
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("invalid limit");
        if (plan.targetType() == null || !Set.of("person", "vehicle", "unknown").contains(plan.targetType())) {
            throw new IllegalArgumentException("invalid target type");
        }
        if (plan.start() != null && plan.end() != null && !plan.start().isBefore(plan.end())) {
            throw new IllegalArgumentException("invalid time range");
        }
        if (plan.clarifications() != null && !plan.clarifications().isEmpty()) {
            throw new IllegalArgumentException("clarification required");
        }

        var cameras = new TreeSet<>(authorizedCameras);
        var requested = plan.cameraIds() == null ? List.<String>of() : plan.cameraIds();
        if (requested.size() > 50 || !authorizedCameras.containsAll(requested)) {
            throw new IllegalArgumentException("unauthorized camera");
        }
        if (!requested.isEmpty()) cameras.retainAll(requested);
        if (plan.locationText() != null) {
            validateText(plan.locationText());
            if (resolvedLocationCameras == null || resolvedLocationCameras.isEmpty()) {
                throw new IllegalArgumentException("location must be resolved");
            }
            cameras.retainAll(resolvedLocationCameras);
        }
        if (cameras.isEmpty()) throw new IllegalArgumentException("no authorized cameras in requested scope");

        var filters = new ArrayList<Map<String, Object>>();
        var must = new ArrayList<Map<String, Object>>();
        filters.add(Map.of("term", Map.of("deleted", false)));
        filters.add(Map.of("terms", Map.of("camera_id", List.copyOf(cameras))));
        if (!plan.targetType().equals("unknown")) filters.add(Map.of("term", Map.of("target_type", plan.targetType())));
        if (plan.start() != null || plan.end() != null) {
            var range = new LinkedHashMap<String, Object>();
            if (plan.start() != null) range.put("gte", plan.start().toString());
            if (plan.end() != null) range.put("lt", plan.end().toString());
            filters.add(Map.of("range", Map.of("source_time", range)));
            filters.add(Map.of("term", Map.of("source_time_origin", "verified")));
        }

        var a = plan.attributes();
        if (a != null) {
            addConcept(must, a.upperColor(), "upper_color^4");
            addConcept(must, a.lowerColor(), "lower_color^4");
            if (hasText(a.vehicleColor())) {
                if ("person".equals(plan.targetType())) throw new IllegalArgumentException("vehicle color cannot filter people");
                if ("unknown".equals(plan.targetType())) filters.add(Map.of("term",Map.of("target_type","vehicle")));
                addConcept(must, a.vehicleColor(), "vehicle_color^4");
            }
            addConcept(must, a.transportType(), "transport_type^4");
            addConcept(must, a.visibleAction(), "visible_action^4");
            if (hasText(a.clothingColor())) {
                addConcept(must, a.clothingColor(), "upper_color^4", "lower_color^4");
            }
            if (a.carrying() != null) {
                if (a.carrying().size() > 12) throw new IllegalArgumentException("too many carrying terms");
                for (String term : a.carrying()) addConcept(must, validateText(term), "carrying^4");
            }
        }
        if (plan.textTerms() != null) {
            if (plan.textTerms().size() > 20) throw new IllegalArgumentException("too many text terms");
            for (String term : plan.textTerms()) {
                addConcept(must, validateText(term), "carrying^2", "transport_type^2", "visible_action^2");
            }
        }
        if (!hasConditions(plan)) {
            throw new IllegalArgumentException("no usable search conditions");
        }
        var bool = new LinkedHashMap<String, Object>();
        bool.put("filter", filters);
        if (!must.isEmpty()) bool.put("must", must);
        return Map.of("size", limit, "track_total_hits", true, "query", Map.of("bool", bool),
                "sort", List.of(Map.of("_score", "desc"), Map.of("source_time", Map.of("order", "desc", "missing", "_last")),
                        Map.of("received_at", "desc"), Map.of("observation_id", "asc")));
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    public static boolean hasConditions(SearchPlan plan) {
        if(!"unknown".equals(plan.targetType())||plan.start()!=null||plan.end()!=null||hasText(plan.locationText())
                ||plan.cameraIds()!=null&&!plan.cameraIds().isEmpty()||plan.textTerms()!=null&&!plan.textTerms().isEmpty())return true;
        var attributes=plan.attributes();
        return attributes!=null&&(hasText(attributes.upperColor())||hasText(attributes.lowerColor())||hasText(attributes.clothingColor())
                ||hasText(attributes.vehicleColor())||hasText(attributes.transportType())||hasText(attributes.visibleAction())
                ||attributes.carrying()!=null&&!attributes.carrying().isEmpty());
    }

    private static String validateText(String s) {
        if (!hasText(s) || s.length() > 200) throw new IllegalArgumentException("invalid search text");
        return s.strip();
    }

    private static void addConcept(List<Map<String,Object>> must, String value, String... preferred) {
        if (!hasText(value)) return;
        String text = validateText(value);
        var fields = new ArrayList<String>();
        fields.add("description^3");
        fields.addAll(List.of(preferred));
        // Legacy vehicle colors may have been stored as clothing fields. They
        // remain lexical evidence, never relabelled as a confirmed body color.
        for (String field : List.of("upper_color", "lower_color", "vehicle_color", "carrying", "transport_type", "visible_action")) {
            if (fields.stream().noneMatch(existing -> existing.equals(field) || existing.startsWith(field+"^"))) fields.add(field);
        }
        String name = "semantic_" + must.size();
        var alternatives=LexicalConcepts.alternatives(text);
        if(alternatives.size()==1)must.add(Map.of("multi_match",Map.of("query",text,"fields",fields,"type","best_fields","operator","and","tie_breaker",0.2,"_name",name)));
        else must.add(Map.of("bool",Map.of("should",alternatives.stream().map(term->Map.of("multi_match",Map.of("query",term,"fields",fields,"type","best_fields","operator","and","tie_breaker",0.2))).toList(),"minimum_should_match",1,"_name",name)));
    }

    /** Controlled labels for named full-text clauses, in the same order as compile. */
    public static List<String> conceptLabels(SearchPlan plan) {
        var labels = new ArrayList<String>();
        var a = plan.attributes();
        if (a != null) {
            if (hasText(a.upperColor())) labels.add("上衣颜色 · " + a.upperColor());
            if (hasText(a.lowerColor())) labels.add("下装颜色 · " + a.lowerColor());
            if (hasText(a.vehicleColor())) labels.add("车身颜色 · " + a.vehicleColor());
            if (hasText(a.transportType())) labels.add("交通工具 · " + a.transportType());
            if (hasText(a.visibleAction())) labels.add("可见动作 · " + a.visibleAction());
            if (hasText(a.clothingColor())) labels.add("衣着颜色 · " + a.clothingColor());
            if (a.carrying() != null) a.carrying().forEach(term -> labels.add("携带物 · " + term));
        }
        if (plan.textTerms() != null) plan.textTerms().forEach(term -> labels.add("语义描述 · " + term));
        return List.copyOf(labels);
    }
}
