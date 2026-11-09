package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.search.SearchPlan;
import cn.trial.videosearch.search.SearchQueryCompiler;
import cn.trial.videosearch.http.SecureJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;

/** Visual recall stays intact; uncertain attributes cannot exclude a candidate. */
@Service
public class AppearanceFusion {
    private static final Logger LOG=LoggerFactory.getLogger(AppearanceFusion.class);
    private static final Set<String> FIELDS=Set.of("upper_color","lower_color","vehicle_color","transport_type","carrying");
    private static final double VISUAL_WEIGHT=.7,SEMANTIC_WEIGHT=.3,RANK_OFFSET=60;
    private final SearchOrchestrator search;
    public AppearanceFusion(SearchOrchestrator search){this.search=search;}
    public record Semantic(String state,Map<String,Object> attributes,List<String> uncertainFields,String modelId,String promptVersion){
        public Map<String,Object> requestValues(){
            var value=new LinkedHashMap<String,Object>();value.put("state",state);value.put("attributes",attributes);value.put("uncertain_fields",uncertainFields);
            value.put("model_id",modelId);value.put("prompt_version",promptVersion);return value;
        }
    }
    public record Outcome(Map<String,Map<String,Object>> rankings,Map<String,Object> summary){}
    public static Semantic validated(Semantic value){
        if(value==null)return new Semantic("disabled",Map.of(),List.of(),null,null);
        if(value.state()==null||!Set.of("completed","disabled","unavailable","timeout").contains(value.state())||value.attributes()==null||!FIELDS.containsAll(value.attributes().keySet())
            ||value.uncertainFields()==null||value.uncertainFields().size()>5||!FIELDS.containsAll(value.uncertainFields()))throw new IllegalArgumentException("Invalid appearance semantics");
        if(value.modelId()!=null&&(value.modelId().isBlank()||value.modelId().length()>200)||value.promptVersion()!=null&&!"appearance-description-v1".equals(value.promptVersion()))throw new IllegalArgumentException("Invalid semantic model");
        if("completed".equals(value.state())&&(value.modelId()==null||value.promptVersion()==null))throw new IllegalArgumentException("Semantic model required");
        for(String field:FIELDS){Object raw=value.attributes().get(field);if(raw==null)continue;
            if(field.equals("carrying")){if(!(raw instanceof List<?> list)||list.size()>12||list.stream().anyMatch(item->!(item instanceof String text)||text.isBlank()||text.length()>100))throw new IllegalArgumentException("Invalid carrying attributes");}
            else if(!(raw instanceof String text)||text.length()>80)throw new IllegalArgumentException("Invalid appearance attribute");
        }return value;
    }
    private static String text(Semantic semantic,String field){return semantic.uncertainFields().contains(field)?null:(String)semantic.attributes().get(field);}
    @SuppressWarnings("unchecked")
    static SearchPlan plan(Semantic semantic,AppearanceIndex.Filter filter){
        boolean person="person".equals(filter.type());
        var carrying=person&&!semantic.uncertainFields().contains("carrying")?(List<String>)semantic.attributes().getOrDefault("carrying",List.of()):List.<String>of();
        var attributes=new SearchPlan.Attributes(person?text(semantic,"upper_color"):null,person?text(semantic,"lower_color"):null,null,carrying,person?null:text(semantic,"transport_type"),null,person?null:text(semantic,"vehicle_color"));
        return new SearchPlan(filter.type(),filter.start(),filter.end(),null,filter.cameras(),attributes,List.of(),List.of());
    }
    public Outcome apply(Semantic raw,List<AppearanceIndex.Ranked> candidates,AppearanceIndex.Filter filter){
        var semantic=validated(raw);var summary=new LinkedHashMap<String,Object>();
        summary.put("state",semantic.state());summary.put("attributes",semantic.attributes());summary.put("uncertain_fields",semantic.uncertainFields());summary.put("model_id",semantic.modelId());summary.put("prompt_version",semantic.promptVersion());
        summary.put("strategy","visual_recall_es_soft_rerank_v1");summary.put("visual_weight",VISUAL_WEIGHT);summary.put("semantic_weight",SEMANTIC_WEIGHT);summary.put("semantic_matches",0);
        if(!"completed".equals(semantic.state())||candidates.isEmpty())return new Outcome(Map.of(),summary);
        var plan=plan(semantic,filter);var labels=SearchQueryCompiler.conceptLabels(plan);
        if(labels.isEmpty()){summary.put("state","no_attributes");return new Outcome(Map.of(),summary);}
        var current=new HashMap<String,AppearanceIndex.Ranked>();candidates.forEach(hit->current.put((String)hit.source().get("observation_id"),hit));
        List<Map<String,Object>> matched;
        try{matched=search.appearanceSemanticMatches(plan,candidates.stream().map(hit->(String)hit.source().get("observation_id")).toList());}
        catch(SecureJsonClient.UpstreamFailure unavailable){LOG.warn("Appearance semantic ES rerank unavailable");summary.put("state","search_unavailable");return new Outcome(Map.of(),summary);}
        var ranks=new LinkedHashMap<String,Integer>();var reasons=new HashMap<String,List<String>>();
        for(var row:matched){String id=Objects.toString(row.get("observation_id"),"");var candidate=current.get(id);if(candidate==null||ranks.containsKey(id))continue;
            var source=candidate.source();
            if(!(row.get("record_version") instanceof Number version)||version.longValue()!=((Number)source.get("record_version")).longValue()
                ||!Objects.equals(source.get("frame_id"),row.get("frame_id"))||!Objects.equals(source.get("camera_id"),row.get("camera_id"))||!Objects.equals(source.get("target_type"),row.get("target_type")))continue;
            ranks.put(id,ranks.size()+1);var why=new ArrayList<String>();
            if(row.get("matched_queries") instanceof List<?> queries)for(int index=0;index<labels.size();index++)if(queries.contains("semantic_"+index))why.add(labels.get(index));
            reasons.put(id,List.copyOf(why));
        }
        summary.put("semantic_matches",ranks.size());if(ranks.isEmpty())return new Outcome(Map.of(),summary);
        var rankings=new LinkedHashMap<String,Map<String,Object>>();
        for(int index=0;index<candidates.size();index++){
            String id=(String)candidates.get(index).source().get("observation_id");Integer rank=ranks.get(id);
            double score=(RANK_OFFSET+1)*(VISUAL_WEIGHT/(RANK_OFFSET+index+1)+(rank==null?0:SEMANTIC_WEIGHT/(RANK_OFFSET+rank)));
            var evidence=new LinkedHashMap<String,Object>();evidence.put("score",score);evidence.put("visual_rank",index+1);evidence.put("semantic_rank",rank);evidence.put("semantic_matches",reasons.getOrDefault(id,List.of()));rankings.put(id,evidence);
        }return new Outcome(rankings,summary);
    }
}
