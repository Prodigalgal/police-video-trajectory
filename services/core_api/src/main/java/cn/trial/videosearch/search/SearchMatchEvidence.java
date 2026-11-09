package cn.trial.videosearch.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explain ES lexical relevance without converting model prose into facts. */
public final class SearchMatchEvidence {
    private SearchMatchEvidence() {}
    public static List<Map<String,Object>> annotate(List<Map<String,Object>> hits, SearchPlan plan) {
        var labels = SearchQueryCompiler.conceptLabels(plan);
        return hits.stream().map(hit -> {
            var result = new LinkedHashMap<String,Object>(hit);
            var reasons = new java.util.ArrayList<String>();
            if (hit.get("matched_queries") instanceof List<?> matched) {
                for (int i = 0; i < labels.size(); i++) if (matched.contains("semantic_"+i)) reasons.add(labels.get(i));
            }
            result.remove("matched_queries");
            if(plan.plateNumber()!=null)reasons.add("已复核完整车牌精确匹配");
            result.put("match_reasons", List.copyOf(reasons));
            result.put("match_basis", plan.plateNumber()!=null?"reviewed_plate_exact":labels.isEmpty() ? "authorized_observation" : "llm_semantics_bm25");
            if(plan.plateNumber()!=null)result.put("match_note","车牌已核对当前画面；不据此推断其他帧属于同一车辆。");
            if (!labels.isEmpty()) result.put("match_note", "画面描述及属性文本与搜索条件相关，请结合原始画面核验。");
            return (Map<String,Object>)result;
        }).toList();
    }
    public static Map<String,Object> summary(List<Map<String,Object>> hits) {
        return summary(hits,null);
    }
    public static Map<String,Object> summary(List<Map<String,Object>> hits,SearchPlan plan) {
        var result = new LinkedHashMap<String,Object>();
        if(plan!=null&&plan.plateNumber()!=null)return Map.of("mode","reviewed_plate_exact","displayed",hits.size(),"message","按已复核完整车牌精确检索；仅关联各自当前画面。");
        result.put("mode", "llm_semantics_bm25");
        result.put("displayed", hits.size());
        result.put("message", "根据画面描述及属性文本检索，按相关度排序；相关度分值不代表识别准确率。");
        return result;
    }
}
