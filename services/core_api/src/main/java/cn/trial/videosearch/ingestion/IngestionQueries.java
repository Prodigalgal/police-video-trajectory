package cn.trial.videosearch.ingestion;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class IngestionQueries {
    private final JdbcTemplate db;
    private final SearchOrchestrator scope;
    private final IngestionPolicy policy;
    public IngestionQueries(JdbcTemplate db,SearchOrchestrator scope,IngestionPolicy policy){this.db=db;this.scope=scope;this.policy=policy;}
    public void requireEnabled(){if(!policy.enabled())throw new AdminService.Rejected(404,"ingestion_disabled");}
    public void camera(String camera){if(!scope.mayReadCamera(camera))throw new AdminService.Rejected(404,"source_unavailable");}
    public Map<String,Object> source(UUID id,boolean lock){
        var rows=db.queryForList("SELECT source_id,camera_id,source_type,source_ref,source_time_origin,enabled FROM sources WHERE source_id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.size()!=1)throw new AdminService.Rejected(404,"source_unavailable");camera((String)rows.getFirst().get("camera_id"));return rows.getFirst();
    }
    public static Map<String,Object> definition(Map<String,Object> source){return Map.of("source_id",source.get("source_id").toString(),"camera_id",source.get("camera_id"),"source_type",source.get("source_type"),"source_ref",source.get("source_ref"),"source_time_origin",source.get("source_time_origin"));}
    public Map<String,Object> sources(int limit,UUID after){
        requireEnabled();if(limit<1||limit>50)throw new IllegalArgumentException();var cameras=scope.readableCameras().stream().sorted().toList();var args=new ArrayList<Object>(cameras);args.add(after);args.add(after);args.add(limit+1);
        if(cameras.isEmpty())return page(List.of(),limit,"source_id");
        var rows=db.queryForList("SELECT s.source_id,s.camera_id,c.display_name,s.source_type,s.source_ref,s.enabled,c.enabled AS camera_enabled,s.source_time_origin FROM sources s JOIN cameras c USING(camera_id) WHERE s.camera_id IN ("+String.join(",",Collections.nCopies(cameras.size(),"?"))+") AND (?::uuid IS NULL OR s.source_id>?) ORDER BY s.source_id LIMIT ?",args.toArray());
        for(var row:rows){row.put("revision",IngestionCommands.sourceRevision(row));row.remove("source_ref");}
        return page(rows,limit,"source_id");
    }
    public Map<String,Object> tasks(String state,String camera,int limit,UUID after,UUID batch){
        requireEnabled();if(limit<1||limit>50||state==null||!Set.of("all","queued","active","failed","completed","disabled").contains(state))throw new IllegalArgumentException();if(camera!=null)camera(camera);
        var cameras=scope.readableCameras().stream().sorted().toList();var args=new ArrayList<Object>(cameras);args.addAll(Arrays.asList(state,state,camera,camera,after,after,batch,batch,limit+1));
        if(cameras.isEmpty())return taskPage(List.of(),limit);
        var rows=db.queryForList("""
            SELECT t.task_id,t.source_id,s.camera_id,c.display_name,s.source_type,s.enabled AS source_enabled,t.state,t.lease_generation,t.lease_expires_at,
              p.batch_id,b.title,p.start_ms,p.end_ms,p.safe_through_ms,p.observed_through_ms,p.priority,p.paused,p.revision,p.failures,
              p.next_attempt_at,p.reader_finished,p.last_error_code,p.last_progress_at,t.updated_at
            FROM ingestion_task_specs p JOIN processing_tasks t USING(task_id) JOIN ingestion_batches b USING(batch_id)
            JOIN sources s ON s.source_id=t.source_id JOIN cameras c ON c.camera_id=s.camera_id
            WHERE s.camera_id IN (%s) AND (?='all' OR t.state=?) AND (?::text IS NULL OR s.camera_id=?)
              AND (?::uuid IS NULL OR t.task_id>?) AND (?::uuid IS NULL OR p.batch_id=?) ORDER BY t.task_id LIMIT ?
            """.formatted(String.join(",",Collections.nCopies(cameras.size(),"?"))),args.toArray());
        var selected=rows.subList(0,Math.min(limit,rows.size()));
        if(!selected.isEmpty()){
            var ids=selected.stream().map(row->row.get("task_id")).toList();String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
            var counts=new HashMap<Object,Map<String,Object>>();
            for(var count:db.queryForList("SELECT c.task_id,count(*) AS registered_frames,count(*) FILTER(WHERE f.analysis_state='completed') AS analyzed_frames FROM capture_outbox c LEFT JOIN frames f USING(frame_id) WHERE c.task_id IN ("+slots+") GROUP BY c.task_id",ids.toArray()))counts.put(count.get("task_id"),count);
            var indices=new HashMap<Object,Map<String,Object>>();
            for(var count:db.queryForList("SELECT c.task_id,count(*) AS observations,count(*) FILTER(WHERE i.completed_at IS NOT NULL) AS indexed_observations FROM capture_outbox c JOIN observations o ON o.frame_id=c.frame_id AND NOT o.deleted LEFT JOIN index_outbox i ON i.observation_id=o.observation_id AND i.record_version=o.record_version AND NOT i.deleted WHERE c.task_id IN ("+slots+") GROUP BY c.task_id",ids.toArray()))indices.put(count.get("task_id"),count);
            for(var row:selected){var count=counts.get(row.get("task_id"));row.put("registered_frames",count==null?0:count.get("registered_frames"));row.put("analyzed_frames",count==null?0:count.get("analyzed_frames"));var index=indices.get(row.get("task_id"));row.put("observations",index==null?0:index.get("observations"));row.put("indexed_observations",index==null?0:index.get("indexed_observations"));}
        }
        return taskPage(rows,limit);
    }
    private Map<String,Object> taskPage(List<Map<String,Object>> rows,int limit){var result=new LinkedHashMap<>(page(rows,limit,"task_id"));result.put("policy",policy.view());result.put("claims_enabled",Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class)));return result;}
    public Map<String,Object> task(UUID id,boolean lock){
        var rows=db.queryForList("SELECT t.*,p.revision,p.paused,p.input_sha256,p.failures FROM processing_tasks t JOIN ingestion_task_specs p USING(task_id) WHERE t.task_id=?"+(lock?" FOR UPDATE OF t,p":""),id);
        if(rows.size()!=1)throw new AdminService.Rejected(404,"ingestion_task_unavailable");source((UUID)rows.getFirst().get("source_id"),false);return rows.getFirst();
    }
    private Map<String,Object> page(List<Map<String,Object>> rows,int limit,String key){
        var selected=rows.subList(0,Math.min(limit,rows.size()));var response=new LinkedHashMap<String,Object>();response.put("items",selected.stream().map(IngestionQueries::external).toList());response.put("has_more",rows.size()>limit);response.put("next_cursor",rows.size()>limit?selected.getLast().get(key).toString():null);return response;
    }
    public static Map<String,Object> external(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();row.forEach((key,value)->result.put(key,value instanceof UUID id?id.toString():value instanceof Timestamp time?time.toInstant().toString():value));return result;}
}
