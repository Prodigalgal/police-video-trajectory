package cn.trial.videosearch.evidence;

import cn.trial.videosearch.index.ObservationIndexReader;
import cn.trial.videosearch.http.SecureJsonClient;
import cn.trial.videosearch.ingest.InferenceReceipts;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Explicit resource checks; file/network work never holds a write transaction. */
@Service
public class ProcessingTraceResources {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProcessingTraceResources.class);
    private static final int MAX_OBSERVATIONS=100,BATCH_SIZE=25,MAX_DOCUMENT_BYTES=128*1024;
    private final JdbcTemplate db;private final ObservationIndexReader index;
    public ProcessingTraceResources(JdbcTemplate db,ObservationIndexReader index){this.db=db;this.index=index;}
    private record Snapshot(List<Map<String,Object>> observations,List<Map<String,Object>> source){}
    private Snapshot snapshot(UUID frame){
        var observations=db.queryForList("SELECT o.observation_id,o.record_version,o.deleted,i.document::text AS document FROM observations o LEFT JOIN index_outbox i ON i.observation_id=o.observation_id AND i.record_version=o.record_version WHERE o.frame_id=? ORDER BY o.observation_id LIMIT 101",frame);
        var source=db.queryForList("SELECT t.source_id,s.camera_id,s.source_ref,s.source_type,s.source_time_origin FROM processing_tasks t JOIN sources s USING(source_id) WHERE t.task_id IN(SELECT task_id FROM frames WHERE frame_id=? UNION SELECT task_id FROM capture_outbox WHERE frame_id=?)",frame,frame);
        return new Snapshot(observations,source);
    }
    public List<Map<String,Object>> verify(UUID frame){
        var before=snapshot(frame);
        if(before.source().size()!=1)throw new InputRejected(409,"trace_source_changed");
        var checks=List.of(indexCheck(before.observations()));
        if(!before.equals(snapshot(frame)))throw new InputRejected(409,"trace_source_changed");
        return checks;
    }
    private Map<String,Object> indexCheck(List<Map<String,Object>> expected){
        if(expected.isEmpty())return check("elasticsearch_document","missing",Map.of("expected",0));
        if(expected.size()>MAX_OBSERVATIONS)return check("elasticsearch_document","not_checked",Map.of("reason","observation_limit_exceeded","limit",MAX_OBSERVATIONS));
        var counts=new LinkedHashMap<String,Integer>();for(String state:List.of("matched","missing","changed","unavailable"))counts.put(state,0);
        for(int start=0;start<expected.size();start+=BATCH_SIZE){
            var batch=expected.subList(start,Math.min(start+BATCH_SIZE,expected.size()));
            Map<UUID,Map<?,?>> actual;
            try{
                var ids=batch.stream().map(row->(UUID)row.get("observation_id")).toList();
                actual=documents(index.read(ids),new HashSet<>(ids));
            }catch(SecureJsonClient.UpstreamFailure|IllegalArgumentException failure){
                LOG.warn("Observation projection verification unavailable for a batch of {}",batch.size(),failure);
                counts.merge("unavailable",batch.size(),Integer::sum);continue;
            }
            for(var row:batch){String state=compare(row,actual.get((UUID)row.get("observation_id")));counts.merge(state,1,Integer::sum);}
        }
        String state=List.of("changed","missing","unavailable").stream().filter(key->counts.get(key)>0).findFirst().orElse("matched");
        var details=new LinkedHashMap<String,Object>(counts);details.put("expected",expected.size());
        return check("elasticsearch_document",state,details);
    }
    private static Map<UUID,Map<?,?>> documents(Map<String,Object> response,Set<UUID> expected){
        if(!(response.get("docs") instanceof List<?> list)||list.size()!=expected.size())throw new IllegalArgumentException("Index response size differs");
        var result=new HashMap<UUID,Map<?,?>>();
        for(Object value:list){
            if(!(value instanceof Map<?,?> row)||!(row.get("_id") instanceof String text))throw new IllegalArgumentException("Index identity missing");
            UUID id=UUID.fromString(text);if(!expected.contains(id)||result.put(id,row)!=null)throw new IllegalArgumentException("Index identity differs");
        }
        return result;
    }
    private static String compare(Map<String,Object> expected,Map<?,?> actual){
        if(actual.containsKey("error"))return "unavailable";
        if(Boolean.FALSE.equals(actual.get("found")))return "missing";
        if(!Boolean.TRUE.equals(actual.get("found"))||!(actual.get("_version") instanceof Number version)||!(actual.get("_source") instanceof Map<?,?> document))return "unavailable";
        if(!(expected.get("document") instanceof String encoded))return "unavailable";
        try{
            var projection=InferenceReceipts.parse(encoded);
            if(!Objects.equals(expected.get("observation_id").toString(),projection.get("observation_id"))||!(projection.get("record_version") instanceof Number expectedVersion)
                    ||expectedVersion.longValue()<1||expectedVersion.doubleValue()!=expectedVersion.longValue()
                    ||expectedVersion.longValue()!=((Number)expected.get("record_version")).longValue()||!Objects.equals(expected.get("deleted"),projection.get("deleted")))return "changed";
            if(version.longValue()!=expectedVersion.longValue()||version.doubleValue()!=version.longValue())return "changed";
            return CanonicalJson.encode(projection,MAX_DOCUMENT_BYTES).sha256().equals(CanonicalJson.encode(document,MAX_DOCUMENT_BYTES).sha256())?"matched":"changed";
        }catch(IllegalArgumentException failure){return "unavailable";}
    }
    private static Map<String,Object> check(String name,String state,Map<String,Object> details){return Map.of("name",name,"state",state,"details",details);}
}
