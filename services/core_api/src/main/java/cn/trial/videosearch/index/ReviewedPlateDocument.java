package cn.trial.videosearch.index;

import cn.trial.videosearch.evidence.ReviewedPlateFacts;
import java.util.*;

/** Shared projection contract for publication and read-only verification. */
public final class ReviewedPlateDocument {
    private ReviewedPlateDocument(){}
    public static Map<String,Object> build(ReviewedPlateFacts.Snapshot snapshot,long revision,Map<?,?> source){
        var document=new LinkedHashMap<String,Object>();
        if("ready".equals(snapshot.outcome())){
            if(source==null||!snapshot.observation().toString().equals(source.get("observation_id"))
                    ||!(source.get("record_version") instanceof Number version)||version.longValue()!=snapshot.version()
                    ||version.doubleValue()!=version.longValue()||snapshot.frame()==null||!snapshot.frame().toString().equals(source.get("frame_id"))
                    ||!snapshot.camera().equals(source.get("camera_id"))||!"vehicle".equals(source.get("target_type"))||!Boolean.FALSE.equals(source.get("deleted")))throw new SourceChanged();
            source.forEach((key,value)->{if(!(key instanceof String name))throw new SourceChanged();document.put(name,value);});
            document.put("reviewed_plate_number",snapshot.number());document.put("plate_proofs",snapshot.proofs());
        }else{
            document.put("observation_id",snapshot.observation().toString());document.put("record_version",snapshot.version());document.put("deleted",true);
        }
        document.put("plate_projection_revision",revision);document.put("plate_fact_sha256",snapshot.sha256());document.put("plate_projection_outcome",snapshot.outcome());
        return document;
    }
    public static final class SourceChanged extends RuntimeException{}
}
