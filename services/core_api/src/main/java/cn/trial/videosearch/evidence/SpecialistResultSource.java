package cn.trial.videosearch.evidence;

import java.util.*;

/** A minimal authorized handoff to the frozen input anchor, without media path guesses. */
public final class SpecialistResultSource {
    private SpecialistResultSource(){}
    public static Map<String,Object> describe(Map<String,Object> job,AnalysisInputs.Verified input){
        try{
            var anchor=SpecialistResult.object(input.manifest().get("anchor"));
            var source=SpecialistResult.object(input.manifest().get("source"));
            String observation=SpecialistResult.text(anchor.get("observation_id"),36);
            if(!UUID.fromString(observation).toString().equals(observation)||!job.get("frame_id").toString().equals(anchor.get("frame_id"))
                ||!Objects.equals(job.get("camera_id"),source.get("camera_id"))||!Objects.equals(input.sha256(),job.get("input_manifest_sha256")))throw new IllegalArgumentException();
            long version=SpecialistResult.integer(anchor.get("record_version"),1,9007199254740991L);
            if(!Set.of("person","vehicle").contains(anchor.get("target_type")))throw new IllegalArgumentException();
            var value=new LinkedHashMap<String,Object>();value.put("schema_version","specialist-result-source-v1");
            for(String key:List.of("job_id","input_id","frame_id","camera_id","input_manifest_sha256","result_sha256")){
                Object raw=job.get(key);value.put(key,raw instanceof UUID id?id.toString():raw);
            }
            value.put("observation_id",observation);value.put("record_version",version);value.put("target_type",anchor.get("target_type"));
            return value;
        }catch(RuntimeException invalid){throw new InputRejected(409,"specialist_input_changed");}
    }
}
