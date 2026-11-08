package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import java.util.*;

final class MediaSourceIdentity {
    private MediaSourceIdentity(){}
    static String fingerprint(Map<String,Object> source){
        var value=new LinkedHashMap<String,Object>();
        for(String key:List.of("source_id","camera_id","source_type","source_ref","source_time_origin")){
            Object field=source.get(key);if(field==null)throw new InputRejected(409,"media_source_changed");value.put(key,field instanceof UUID id?id.toString():field);
        }
        return CanonicalJson.encode(value,8192).sha256();
    }
}
