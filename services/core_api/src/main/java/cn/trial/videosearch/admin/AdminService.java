package cn.trial.videosearch.admin;

import cn.trial.videosearch.ingest.AnalysisQueue;
import cn.trial.videosearch.ingest.PipelineOperations;
import cn.trial.videosearch.ingest.ModuleQueue;
import cn.trial.videosearch.ingest.ModuleResultSource;
import cn.trial.videosearch.search.SearchCursorCodec;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Scoped operations for the single authenticated administrator. */
@Service
public class AdminService {
    public static class Rejected extends RuntimeException {
        public final int status; public final String code;
        public Rejected(int status,String code) { super(code);this.status=status;this.code=code; }
    }
    public record CameraUpdate(String displayName,String locationKey,List<String> aliases,Boolean enabled,String expectedRevision) {}
    public record Retry(UUID requestId,long expectedRevision,String reason) {}
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private final AnalysisQueue queue;
    private final PipelineOperations operations;
    private final ModuleQueue modules;
    private final List<String> scope;
    private final ObjectMapper json=new ObjectMapper();
    public AdminService(JdbcTemplate db,SearchOrchestrator search,AnalysisQueue queue,PipelineOperations operations,ModuleQueue modules) {
        this.db=db;this.search=search;this.queue=queue;this.operations=operations;this.modules=modules;this.scope=new ArrayList<>(new TreeSet<>(search.readableCameras()));
    }
    private List<Map<String,Object>> rows(String sql,Object... tail) {
        var args=new ArrayList<Object>(scope);args.addAll(Arrays.asList(tail));
        return db.queryForList(sql.replace(":scope",String.join(",",Collections.nCopies(scope.size(),"?"))),args.toArray());
    }
    private long count(String sql) { return ((Number)rows(sql).getFirst().get("count")).longValue(); }
    private boolean claims() { return Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class)); }
    private void cameraAllowed(String camera) { if(!search.mayReadCamera(camera)) throw new Rejected(404,"camera_unavailable"); }
    public Map<String,Object> overview() {
        var source=rows("SELECT count(*) AS total,count(*) FILTER(WHERE enabled) AS enabled FROM sources WHERE camera_id IN (:scope)").getFirst();
        var counts=new LinkedHashMap<String,Object>();
        counts.put("cameras",count("SELECT count(*) AS count FROM cameras WHERE camera_id IN (:scope)"));
        counts.put("sources",source.get("total"));counts.put("enabled_sources",source.get("enabled"));
        counts.put("frames",count("SELECT count(*) AS count FROM frames f JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id WHERE s.camera_id IN (:scope)"));
        counts.put("observations",count("SELECT count(*) AS count FROM observations WHERE NOT deleted AND camera_id IN (:scope)"));
        counts.put("pending_index",count("SELECT count(*) AS count FROM index_outbox i JOIN observations o USING(observation_id) WHERE i.completed_at IS NULL AND o.camera_id IN (:scope)"));
        var analysis=rows("SELECT j.state,count(*) AS count FROM analysis_jobs j JOIN frames f USING(frame_id) JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s USING(source_id) WHERE s.camera_id IN (:scope) GROUP BY j.state ORDER BY j.state");
        var queueLoad=rows("""
                SELECT count(*) FILTER(WHERE j.state='queued' AND j.next_attempt_at<=clock_timestamp()) AS ready,
                  count(*) FILTER(WHERE j.state='queued' AND j.next_attempt_at>clock_timestamp()) AS retry_waiting,
                  count(*) FILTER(WHERE j.state='active' AND j.lease_expires_at>clock_timestamp()) AS active,
                  count(*) FILTER(WHERE j.state='active' AND (j.lease_expires_at IS NULL OR j.lease_expires_at<=clock_timestamp())) AS expired
                FROM analysis_jobs j JOIN frames f USING(frame_id) JOIN processing_tasks t ON t.task_id=f.task_id
                JOIN sources s USING(source_id) WHERE s.camera_id IN (:scope)
                """).getFirst();
        var moduleLoad=rows("SELECT j.module_key,j.state,count(*) AS count FROM analysis_module_jobs j JOIN capture_outbox c USING(frame_id) WHERE c.camera_id IN (:scope) GROUP BY j.module_key,j.state ORDER BY j.module_key,j.state");
        return Map.of("observed_at",Instant.now().toString(),"counts",counts,"analysis",analysis,
                "analysis_claims_enabled",claims(),"queue_capacity",queue.pendingLimit(),"scope",scope,
                "queue_policy",queue.policy(),"queue_load",queueLoad,"module_policy",modules.policy(),"module_load",moduleLoad);
    }
    public Map<String,Object> cameras() {
        var list=rows("""
                SELECT c.camera_id,c.display_name,c.location_key,c.enabled,
                 COALESCE((SELECT json_agg(a.alias ORDER BY a.alias) FROM camera_aliases a WHERE a.camera_id=c.camera_id),'[]'::json)::text AS aliases_json
                FROM cameras c WHERE c.camera_id IN (:scope) ORDER BY c.camera_id
                """);
        return Map.of("items",list.stream().map(this::cameraView).toList());
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> cameraView(Map<String,Object> row) {
        var view=new LinkedHashMap<String,Object>();
        for(String key:List.of("camera_id","display_name","location_key","enabled")) view.put(key,row.get(key));
        view.put("aliases",json.readValue((String)row.get("aliases_json"),List.class));
        view.put("revision",SearchCursorCodec.fingerprint(view));return view;
    }
    private Map<String,Object> lockedCamera(String camera) {
        var found=db.queryForList("SELECT camera_id,display_name,location_key,enabled FROM cameras WHERE camera_id=? FOR UPDATE",camera);
        if(found.size()!=1) throw new Rejected(404,"camera_unavailable");
        var row=new LinkedHashMap<String,Object>(found.getFirst());
        row.put("aliases_json",db.queryForObject("SELECT COALESCE(json_agg(alias ORDER BY alias),'[]'::json)::text FROM camera_aliases WHERE camera_id=?",String.class,camera));
        return cameraView(row);
    }
    static List<String> aliases(String camera,CameraUpdate request) {
        if(request==null || request.enabled()==null || request.displayName()==null || request.displayName().isBlank()
                || request.displayName().length()>200 || request.displayName().chars().anyMatch(Character::isISOControl)
                || request.locationKey()!=null && (request.locationKey().length()>100 || request.locationKey().chars().anyMatch(Character::isISOControl))
                || request.aliases()==null || request.aliases().size()>18 || request.expectedRevision()==null || !request.expectedRevision().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid camera update");
        var result=new TreeSet<String>();result.add(camera);result.add(request.displayName().strip());
        for(String alias:request.aliases()) {
            if(alias==null || alias.isBlank() || alias.length()>200 || alias.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid alias");
            result.add(alias.strip());
        }
        return List.copyOf(result);
    }
    @Transactional
    public Map<String,Object> updateCamera(String camera,CameraUpdate request,String actor) {
        cameraAllowed(camera);var aliases=aliases(camera,request);
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var previous=lockedCamera(camera);
        if(!previous.get("revision").equals(request.expectedRevision())) throw new Rejected(409,"camera_revision_conflict");
        String location=request.locationKey()==null || request.locationKey().isBlank()?null:request.locationKey().strip();
        db.update("UPDATE cameras SET display_name=?,location_key=?,enabled=? WHERE camera_id=?",request.displayName().strip(),location,request.enabled(),camera);
        db.update("DELETE FROM camera_aliases WHERE camera_id=?",camera);
        for(String alias:aliases) db.update("INSERT INTO camera_aliases(alias,camera_id) VALUES(?,?)",alias,camera);
        if(!request.enabled()) db.update("UPDATE sources SET enabled=false WHERE camera_id=? AND enabled",camera);
        var current=lockedCamera(camera);UUID event=UUID.randomUUID();
        audit(actor,"camera.update",json.writeValueAsString(Map.of("camera_id",camera,"before_revision",previous.get("revision"),"after_revision",current.get("revision"))),event);
        return Map.of("camera",current,"request_id",event.toString());
    }
    public Map<String,Object> jobs(String state,String camera,int limit,UUID after) {
        if(!Set.of("all","queued","active","failed","completed").contains(state) || limit<1 || limit>50) throw new IllegalArgumentException("invalid task page");
        if(camera!=null) cameraAllowed(camera);
        var found=rows("""
                SELECT j.job_id,j.frame_id,s.camera_id,c.display_name AS camera_name,j.state,j.attempts,j.lease_generation AS revision,
                  j.last_error_code,j.next_attempt_at,j.updated_at,j.lease_expires_at,s.enabled AS source_enabled,
                  CASE WHEN j.state='active' AND (j.lease_expires_at IS NULL OR j.lease_expires_at<=clock_timestamp()) THEN 'lease_expired'
                    WHEN j.state='queued' AND j.next_attempt_at>clock_timestamp() THEN 'retry_wait'
                    ELSE j.state END AS scheduling_state,
                  EXISTS(SELECT 1 FROM observations o WHERE o.frame_id=f.frame_id) AS has_result
                FROM analysis_jobs j JOIN frames f USING(frame_id) JOIN processing_tasks t ON t.task_id=f.task_id
                JOIN sources s USING(source_id) JOIN cameras c ON c.camera_id=s.camera_id
                WHERE s.camera_id IN (:scope) AND (?='all' OR j.state=?) AND (?::text IS NULL OR s.camera_id=?)
                  AND (?::uuid IS NULL OR j.job_id>?) ORDER BY j.job_id LIMIT ?
                """,state,state,camera,camera,after,after,limit+1);
        boolean enabled=claims(),more=found.size()>limit;var items=new ArrayList<Map<String,Object>>();
        for(var raw:found.subList(0,Math.min(limit,found.size()))) {
            var row=new LinkedHashMap<String,Object>(raw);
            for(var entry:row.entrySet()) if(entry.getValue() instanceof Timestamp time) entry.setValue(time.toInstant().toString());
            row.put("can_retry",enabled && Boolean.TRUE.equals(row.get("source_enabled")) && "failed".equals(row.get("state")) && !Boolean.TRUE.equals(row.get("has_result")));
            items.add(row);
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("has_more",more);
        result.put("next_cursor",more?items.getLast().get("job_id").toString():null);result.put("processing_paused",!enabled);
        result.put("queue_policy",queue.policy());return result;
    }
    @Transactional
    public Map<String,Object> retry(UUID job,Retry request,String actor) {
        if(request==null || request.requestId()==null || request.expectedRevision()<0 || request.reason()==null || request.reason().isBlank() || request.reason().length()>300)
            throw new IllegalArgumentException("invalid recovery request");
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var found=rows("SELECT s.enabled,s.camera_id FROM analysis_jobs j JOIN frames f USING(frame_id) JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s USING(source_id) WHERE s.camera_id IN (:scope) AND j.job_id=? FOR UPDATE OF s",job);
        if(found.size()!=1) throw new Rejected(404,"job_unavailable");
        if(!claims() || !Boolean.TRUE.equals(found.getFirst().get("enabled"))) throw new Rejected(409,"processing_paused");
        return operations.retry(job,new PipelineOperations.Recovery(request.requestId(),request.expectedRevision(),actor,request.reason()));
    }

    public Map<String,Object> moduleJobs(String state,String camera,String kind,int limit,UUID after){
        if(!Set.of("all","queued","active","completed","failed").contains(state) || limit<1 || limit>50 ||
                kind!=null && !ModuleResultSource.KINDS.contains(kind))throw new IllegalArgumentException("invalid module page");
        if(camera!=null)cameraAllowed(camera);
        var found=rows("""
                SELECT j.job_id,j.frame_id,j.module_key,j.state,j.attempts,j.lease_generation AS revision,
                  j.last_error_code,j.next_attempt_at,j.updated_at,j.lease_expires_at,j.source_id,r.source_sha256,r.model_id,r.prompt_version,
                  c.camera_id,a.display_name AS camera_name,(s.enabled AND a.enabled) AS source_enabled,
                  (m.result IS NOT NULL OR m.completed_at IS NOT NULL) AS has_result,
                  CASE WHEN (m.result IS NULL)<>(m.completed_at IS NULL) OR ((j.state='completed')<>(m.result IS NOT NULL)) THEN 'inconsistent'
                    WHEN j.state='active' AND (j.lease_expires_at IS NULL OR j.lease_expires_at<=clock_timestamp()) THEN 'lease_expired'
                    WHEN j.state='queued' AND j.next_attempt_at>clock_timestamp() THEN 'retry_wait' ELSE j.state END AS scheduling_state
                FROM analysis_module_jobs j JOIN analysis_modules m USING(frame_id,module_key)
                JOIN analysis_result_sources r ON r.source_id=j.source_id JOIN capture_outbox c ON c.frame_id=j.frame_id
                JOIN processing_tasks t ON t.task_id=c.task_id JOIN sources s ON s.source_id=t.source_id JOIN cameras a ON a.camera_id=c.camera_id
                WHERE c.camera_id IN (:scope) AND (?='all' OR j.state=?) AND (?::text IS NULL OR c.camera_id=?)
                  AND (?::text IS NULL OR j.module_key=?) AND (?::uuid IS NULL OR j.job_id>?) ORDER BY j.job_id LIMIT ?
                """,state,state,camera,camera,kind,kind,after,after,limit+1);
        boolean enabled=claims(),more=found.size()>limit;var items=new ArrayList<Map<String,Object>>();
        for(var raw:found.subList(0,Math.min(limit,found.size()))){
            var row=new LinkedHashMap<>(raw);
            for(var entry:row.entrySet())if(entry.getValue() instanceof Timestamp time)entry.setValue(time.toInstant().toString());
            row.put("can_retry",enabled && Boolean.TRUE.equals(row.get("source_enabled")) && "failed".equals(row.get("state")) && !Boolean.TRUE.equals(row.get("has_result")));
            items.add(row);
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("has_more",more);
        result.put("next_cursor",more?items.getLast().get("job_id").toString():null);result.put("processing_paused",!enabled);
        result.put("queue_policy",modules.policy());return result;
    }

    @Transactional
    public Map<String,Object> retryModule(UUID job,Retry request,String actor){
        if(request==null)throw new IllegalArgumentException("module recovery required");
        var found=rows("SELECT c.camera_id FROM analysis_module_jobs j JOIN capture_outbox c USING(frame_id) WHERE c.camera_id IN (:scope) AND j.job_id=?",job);
        if(found.size()!=1)throw new Rejected(404,"module_job_unavailable");
        return modules.retry(job,request.requestId(),request.expectedRevision(),actor,request.reason());
    }
    @Transactional
    public Map<String,Object> pause(String actor) {
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var parameters=scope.toArray();
        int disabled=db.update("UPDATE sources SET enabled=false WHERE enabled AND camera_id IN ("+String.join(",",Collections.nCopies(scope.size(),"?"))+")",parameters);
        db.update("UPDATE processing_controls SET enabled=false,updated_at=clock_timestamp() WHERE control_key='analysis_claims'");
        UUID request=UUID.randomUUID();audit(actor,"processing.pause","committed",request);
        return Map.of("paused",true,"sources_disabled",disabled,"request_id",request.toString());
    }
    private void audit(String actor,String action,String result,UUID request) { db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,action,result,request); }
}
