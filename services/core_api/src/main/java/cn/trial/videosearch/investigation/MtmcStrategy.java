package cn.trial.videosearch.investigation;

import org.springframework.stereotype.Component;
import java.util.*;

/** A replaceable ranking policy. Scores are relevance evidence, never identity probabilities. */
public interface MtmcStrategy {
    record Evidence(double appearance,int attributeMatches,int attributeConflicts,String plateRelation,String temporalRelation,Double deltaSeconds,int windowSeconds){}
    record Decision(double score,List<String> reasons,List<String> conflicts){}
    String id();
    String label();
    Map<String,Object> parameters();
    Decision evaluate(Evidence evidence);
}

@Component
class AppearanceMtmcStrategy implements MtmcStrategy {
    public String id(){return "appearance-v1";}
    public String label(){return "多帧外观关联";}
    public Map<String,Object> parameters(){return Map.of("appearance_aggregation","mean_top_3_sightings","identity_probability",false);}
    public Decision evaluate(Evidence evidence){return new Decision(evidence.appearance(),List.of("按同一特征空间中的多帧外观相似度排序"),List.of());}
}

@Component
class EvidenceFusionMtmcStrategy implements MtmcStrategy {
    public String id(){return "evidence-fusion-v1";}
    public String label(){return "外观、车牌与时序融合";}
    public Map<String,Object> parameters(){return Map.of("attribute_match_bonus",.03,"attribute_conflict_penalty",.02,"plate_match_bonus",.15,"plate_conflict_penalty",.20,"verified_time_bonus",.02,"hard_spatial_exclusion",false,"identity_probability",false);}
    public Decision evaluate(Evidence evidence){
        double score=evidence.appearance();var reasons=new ArrayList<String>();var conflicts=new ArrayList<String>();
        reasons.add("以多帧外观相似度作为主要关联依据");
        if(evidence.attributeMatches()>0){score+=.03*evidence.attributeMatches();reasons.add("存在一致且未标记不确定的可见属性");}
        if(evidence.attributeConflicts()>0){score-=.02*evidence.attributeConflicts();conflicts.add("部分可见属性不同，作为软冲突保留");}
        switch(evidence.plateRelation()){
            case "matches" -> {score+=.15;reasons.add("当前来源绑定的已复核车牌一致");}
            case "conflicts" -> {score-=.20;conflicts.add("当前来源绑定的已复核车牌不同");}
            case "inconsistent_track" -> conflicts.add("轨迹片段内已复核车牌存在分歧，未作为匹配依据");
            default -> {}
        }
        if("unknown".equals(evidence.temporalRelation()))reasons.add("拍摄时间尚未核实，仅提供外观关联候选");
        else {
            score+=.02/(1+Math.abs(evidence.deltaSeconds()==null?0:evidence.deltaSeconds())/Math.max(1,evidence.windowSeconds()));
            reasons.add("overlap".equals(evidence.temporalRelation())?"已核实拍摄时段重叠，未将跨镜同时出现作为冲突":"具有已核实拍摄时间，已比较出现时序");
        }
        return new Decision(score,List.copyOf(reasons),List.copyOf(conflicts));
    }
}
