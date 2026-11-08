package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

/** Durable submission work. Inference remains in SpecialistJobs. */
@Service
public class SpecialistCandidates {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String WAITING="'ready','waiting_source','waiting_configuration','waiting_budget','waiting_result','waiting_review'";
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final SearchOrchestrator search;
    private final SpecialistCandidatePolicy policy;
    public SpecialistCandidates(JdbcTemplate db,PlatformTransactionManager manager,SearchOrchestrator search,SpecialistCandidatePolicy policy){
        this.db=db;this.transaction=new TransactionTemplate(manager);this.transaction.setTimeout(10);this.search=search;this.policy=policy;
    }
    public record Lease(UUID id,UUID frame,String camera,UUID target,String evidenceSha,String selectionSha,String owner,long generation,
                        UUID anchor,Long anchorVersion,UUID input,int failures){}
    public record Source(UUID observation,long version,String reason,boolean invalid){}
    private record Snapshot(UUID target,String policySha,String selectionSha,String reason){}
    public record Retry(UUID requestId,long expectedRevision,String reason){}
    public boolean enabled(){return policy.enabled();}
    public boolean running(){return policy.enabled()&&Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));}
    private void lock(){db.execute("SELECT pg_advisory_xact_lock(918273691)");}
    public static UUID request(UUID candidate,String step){return UUID.nameUUIDFromBytes(("specialist-candidate:"+candidate+":"+step).getBytes(StandardCharsets.UTF_8));}
    private List<String> cameras(){return search.readableCameras().stream().sorted().toList();}
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}

    public int scan(){
        if(!running()||cameras().isEmpty())return 0;
        return transaction.execute(status->{
            lock();long pending=db.queryForObject("SELECT count(*) FROM specialist_candidates WHERE state IN ("+WAITING+",'active')",Long.class);
            int available=(int)Math.min(policy.batchSize(),policy.pendingLimit()-pending);if(available<=0)return 0;
            var parameters=new ArrayList<Object>();parameters.add(policy.maxAgeSeconds());parameters.add(policy.maxAgeSeconds());parameters.addAll(cameras());parameters.add(available);
            var sources=db.queryForList("""
                SELECT c.*,c.plan::text AS plan_text,c.registered_at+(? * interval '1 second') AS candidate_expires
                FROM capture_outbox c LEFT JOIN specialist_candidates p ON p.frame_id=c.frame_id
                WHERE p.frame_id IS NULL AND c.enqueued_at IS NOT NULL
                AND c.registered_at>clock_timestamp()-(? * interval '1 second') AND c.camera_id IN (%s)
                AND c.plan#>>'{selection_metrics,specialist_selection,schema_version}'='specialist-selection-v2'
                AND c.plan#>>'{selection_metrics,specialist_selection,state}'='complete'
                AND c.plan#>>'{selection_metrics,specialist_selection,mode}'='select'
                AND c.plan#>'{selection_metrics,specialist_selection,modules}' @> '[{"module":"license_plate","decision":"selected"}]'::jsonb
                ORDER BY c.registered_at,c.frame_id LIMIT ?
                """.formatted(placeholders(cameras().size())),parameters.toArray());
            var snapshots=new LinkedHashMap<UUID,Snapshot>();
            for(var source:sources)snapshots.put((UUID)source.get("frame_id"),snapshot(source));
            var ids=snapshots.values().stream().map(Snapshot::target).filter(Objects::nonNull).distinct().toList();
            var targets=new HashMap<UUID,Map<String,Object>>();
            if(!ids.isEmpty())for(var target:db.queryForList("SELECT target_id,frame_id,object_type FROM detected_targets WHERE target_id IN ("+placeholders(ids.size())+")",ids.toArray()))targets.put((UUID)target.get("target_id"),target);
            int inserted=0;
            for(var source:sources){
                UUID frame=(UUID)source.get("frame_id");var snapshot=snapshots.get(frame);var target=targets.get(snapshot.target());
                boolean valid=snapshot.reason()==null&&target!=null&&frame.equals(target.get("frame_id"))&&"vehicle".equals(target.get("object_type"));
                inserted+=db.update("""
                    INSERT INTO specialist_candidates(candidate_id,frame_id,camera_id,target_id,evidence_sha256,selection_sha256,policy_sha256,state,reason,expires_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(frame_id) DO NOTHING
                    """,request(frame,"candidate"),frame,source.get("camera_id"),valid?snapshot.target():null,source.get("payload_sha256"),snapshot.selectionSha(),snapshot.policySha(),
                    valid?"ready":"invalid",valid?"candidate_ready":snapshot.reason()==null?"selected_target_missing":snapshot.reason(),source.get("candidate_expires"));
            }
            return inserted;
        });
    }
    private static Snapshot snapshot(Map<String,Object> source){
        try{return validatedSnapshot(source);}
        catch(RuntimeException invalid){return new Snapshot(null,null,CanonicalJson.sha256(String.valueOf(source.get("plan_text"))),"selection_evidence_invalid");}
    }
    private static Snapshot validatedSnapshot(Map<String,Object> source){
        var plan=SpecialistResult.object(JSON.readValue((String)source.get("plan_text"),Object.class));
        var selection=SpecialistResult.object(SpecialistResult.object(plan.get("selection_metrics")).get("specialist_selection"));
        var encoded=CanonicalJson.encode(selection,2*1024*1024);String policy=selection.get("policy_sha256") instanceof String value&&value.matches("[a-f0-9]{64}")?value:null;
        try{
            if(encoded.payload().getBytes(StandardCharsets.UTF_8).length>128*1024||policy==null)throw new IllegalArgumentException();
            var descriptor=Map.of("frame_id",source.get("frame_id").toString(),"camera_id",source.get("camera_id"),"evidence_sha256",source.get("payload_sha256"),"image_width",plan.get("image_width"),"image_height",plan.get("image_height"));
            SpecialistAdmission.validateSource(descriptor,source,plan,selection);
            UUID target=null;
            for(Object raw:(List<?>)selection.get("modules")){
                var module=SpecialistResult.object(raw);if(!"license_plate".equals(module.get("module")))continue;
                if(target!=null||!"selected".equals(module.get("decision"))||!(module.get("selected_target_ids") instanceof List<?> ids)||ids.size()!=1)throw new IllegalArgumentException();
                String value=(String)ids.getFirst();target=UUID.fromString(value);if(!target.toString().equals(value))throw new IllegalArgumentException();
            }
            if(target==null)throw new IllegalArgumentException();return new Snapshot(target,policy,encoded.sha256(),null);
        }catch(RuntimeException invalid){return new Snapshot(null,policy,encoded.sha256(),"selection_evidence_invalid");}
    }

    public Optional<Lease> claim(String owner){
        if(owner==null||!owner.matches("[A-Za-z0-9_.-]{1,70}"))throw new IllegalArgumentException("Invalid candidate worker");
        if(!running()||cameras().isEmpty())return Optional.empty();
        return transaction.execute(status->{
            lock();
            db.update("UPDATE specialist_candidates SET state=CASE WHEN failures+1>=? THEN 'failed' ELSE 'ready' END,failures=failures+1,reason='producer_lease_expired',revision=revision+1,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+interval '5 seconds',updated_at=clock_timestamp() WHERE state='active' AND lease_expires_at<=clock_timestamp()",policy.maxFailures());
            db.update("UPDATE specialist_candidates SET state='expired',reason='candidate_expired',revision=revision+1,updated_at=clock_timestamp() WHERE state IN ("+WAITING+") AND expires_at<=clock_timestamp()");
            if(db.queryForObject("SELECT count(*) FROM specialist_candidates WHERE state='active'",Long.class)>=policy.activeLimit())return Optional.empty();
            var parameters=new ArrayList<Object>(cameras());parameters.add(owner);parameters.add(policy.leaseSeconds());
            var rows=db.queryForList("""
                WITH selected AS(SELECT candidate_id FROM specialist_candidates WHERE state IN (%s)
                  AND camera_id IN (%s) AND next_attempt_at<=clock_timestamp() AND expires_at>clock_timestamp()
                  ORDER BY next_attempt_at,created_at,candidate_id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE specialist_candidates p SET state='active',revision=revision+1,lease_owner=?,lease_generation=lease_generation+1,
                  lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                FROM selected WHERE p.candidate_id=selected.candidate_id RETURNING p.*
                """.formatted(WAITING,placeholders(cameras().size())),parameters.toArray());
            return rows.isEmpty()?Optional.empty():Optional.of(lease(rows.getFirst()));
        });
    }
    private static Lease lease(Map<String,Object> row){return new Lease((UUID)row.get("candidate_id"),(UUID)row.get("frame_id"),(String)row.get("camera_id"),(UUID)row.get("target_id"),(String)row.get("evidence_sha256"),(String)row.get("selection_sha256"),(String)row.get("lease_owner"),((Number)row.get("lease_generation")).longValue(),(UUID)row.get("anchor_observation_id"),row.get("anchor_record_version") instanceof Number n?n.longValue():null,(UUID)row.get("input_id"),((Number)row.get("failures")).intValue());}

    public Source source(Lease lease){
        var rows=db.queryForList("""
            SELECT c.*,c.plan::text AS plan_text,f.analysis_state,t.observation_ordinal,
                   o.observation_id,o.record_version,o.deleted,o.target_type
            FROM capture_outbox c JOIN frames f ON f.frame_id=c.frame_id
            LEFT JOIN detected_targets t ON t.frame_id=c.frame_id AND t.target_id=?
            LEFT JOIN observations o ON o.frame_id=c.frame_id AND o.object_ordinal=t.observation_ordinal
            WHERE c.frame_id=?
            """,lease.target(),lease.frame());
        if(rows.size()!=1)return new Source(null,0,"candidate_source_missing",true);
        var row=rows.getFirst();var snapshot=snapshot(row);
        if(snapshot.reason()!=null||!Objects.equals(snapshot.target(),lease.target())||!snapshot.selectionSha().equals(lease.selectionSha())
            ||!Objects.equals(row.get("payload_sha256"),lease.evidenceSha())||!Objects.equals(row.get("camera_id"),lease.camera()))return new Source(null,0,"candidate_source_changed",true);
        if(!"completed".equals(row.get("analysis_state")))return new Source(null,0,"main_analysis_pending",false);
        if(row.get("observation_id")==null)return new Source(null,0,"vehicle_observation_pending",false);
        if(Boolean.TRUE.equals(row.get("deleted"))||!"vehicle".equals(row.get("target_type")))return new Source(null,0,"candidate_source_withdrawn",true);
        long version=((Number)row.get("record_version")).longValue();UUID observation=(UUID)row.get("observation_id");
        if(lease.anchor()!=null&&(!lease.anchor().equals(observation)||!Objects.equals(lease.anchorVersion(),version)))return new Source(null,0,"candidate_observation_changed",true);
        return new Source(observation,version,null,false);
    }
    public boolean bindAnchor(Lease lease,Source source){
        return db.update("UPDATE specialist_candidates SET anchor_observation_id=?,anchor_record_version=? WHERE candidate_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp() AND (anchor_observation_id IS NULL OR anchor_observation_id=? AND anchor_record_version=?)",source.observation(),source.version(),lease.id(),lease.owner(),lease.generation(),source.observation(),source.version())==1;
    }
    public boolean bindInput(Lease lease,UUID input){return db.update("UPDATE specialist_candidates SET input_id=? WHERE candidate_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp() AND (input_id IS NULL OR input_id=?)",input,lease.id(),lease.owner(),lease.generation(),input)==1;}
    public boolean finish(Lease lease,SpecialistCandidateState state,String reason,int delay,UUID job,boolean failed){
        if(state==SpecialistCandidateState.ACTIVE||!reason.matches("[a-z_]{1,80}")||delay<0||delay>300)throw new IllegalArgumentException("Invalid candidate outcome");
        if((state==SpecialistCandidateState.SUBMITTED||state==SpecialistCandidateState.REUSED)!=(job!=null))throw new IllegalArgumentException("Invalid candidate job reference");
        return db.update("""
            UPDATE specialist_candidates SET state=?,reason=?,failures=failures+?,revision=revision+1,
              lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(? * interval '1 second'),
              job_id=?,reference_job_id=?,updated_at=clock_timestamp()
            WHERE candidate_id=? AND state='active' AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
            """,state.key(),reason,failed?1:0,delay,state==SpecialistCandidateState.SUBMITTED?job:null,state==SpecialistCandidateState.REUSED?job:null,lease.id(),lease.owner(),lease.generation())==1;
    }

    public Map<String,Object> list(String camera,String state,int limit,UUID after){
        if(limit<1||limit>50||camera!=null&&!search.mayReadCamera(camera)||state!=null&&Arrays.stream(SpecialistCandidateState.values()).noneMatch(v->v.key().equals(state)))throw new IllegalArgumentException("Invalid candidate filter");
        var allowed=cameras();var parameters=new ArrayList<Object>(allowed);parameters.add(camera);parameters.add(camera);parameters.add(state);parameters.add(state);parameters.add(after);parameters.add(after);parameters.add(limit+1);
        var rows=allowed.isEmpty()?List.<Map<String,Object>>of():db.queryForList("SELECT *,expires_at>clock_timestamp() AS eligible,lease_expires_at>clock_timestamp() AS alive FROM specialist_candidates WHERE camera_id IN ("+placeholders(allowed.size())+") AND (?::text IS NULL OR camera_id=?) AND (?::text IS NULL OR state=?) AND (?::uuid IS NULL OR candidate_id>?) ORDER BY candidate_id LIMIT ?",parameters.toArray());
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));var response=new LinkedHashMap<String,Object>();
        response.put("items",page.stream().map(SpecialistCandidates::view).toList());response.put("has_more",more);response.put("next_cursor",more?page.getLast().get("candidate_id").toString():null);
        response.put("enabled",enabled());response.put("claims_enabled",Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class)));
        response.put("policy",Map.of("pending_limit",policy.pendingLimit(),"batch",policy.batchSize(),"active_limit",policy.activeLimit(),"max_age_seconds",policy.maxAgeSeconds(),"max_failures",policy.maxFailures()));
        return response;
    }
    private static Map<String,Object> view(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("candidate_id","frame_id","camera_id","target_id","state","reason","revision","failures","next_attempt_at","expires_at","input_id","job_id","reference_job_id","created_at","updated_at")){
            Object value=row.get(key);result.put(key,value instanceof UUID id?id.toString():value instanceof Timestamp stamp?stamp.toInstant().toString():value);
        }
        result.put("phase","active".equals(row.get("state"))&&!Boolean.TRUE.equals(row.get("alive"))?"lease_expired":row.get("state"));result.put("can_retry","failed".equals(row.get("state"))&&Boolean.TRUE.equals(row.get("eligible")));return result;
    }
    public Map<String,Object> retry(UUID id,Retry body,String actor){
        if(id==null||body==null||body.requestId()==null||body.expectedRevision()<1||body.reason()==null||body.reason().isBlank()||body.reason().length()>300||actor==null||actor.isBlank()||actor.length()>100)throw new IllegalArgumentException("Invalid candidate recovery");
        var encoded=CanonicalJson.encode(Map.of("candidate",id.toString(),"revision",body.expectedRevision(),"reason",body.reason().strip()),8192);
        return transaction.execute(status->{
            lock();var rows=db.queryForList("SELECT *,expires_at>clock_timestamp() AS eligible,lease_expires_at>clock_timestamp() AS alive FROM specialist_candidates WHERE candidate_id=? FOR UPDATE",id);
            if(rows.size()!=1||!search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"candidate_unavailable");
            var previous=db.queryForList("SELECT candidate_id,actor_id,request_sha256,response::text AS payload FROM specialist_candidate_recoveries WHERE request_id=?",body.requestId());
            if(!previous.isEmpty()){
                var row=previous.getFirst();if(!id.equals(row.get("candidate_id"))||!actor.equals(row.get("actor_id"))||!encoded.sha256().equals(row.get("request_sha256")))throw new InputRejected(409,"candidate_recovery_conflict");
                return SpecialistResult.object(JSON.readValue((String)row.get("payload"),Object.class));
            }
            var row=rows.getFirst();if(!"failed".equals(row.get("state"))||((Number)row.get("revision")).longValue()!=body.expectedRevision())throw new InputRejected(409,"candidate_revision_conflict");
            if(!Boolean.TRUE.equals(row.get("eligible")))throw new InputRejected(409,"candidate_expired");
            long pending=db.queryForObject("SELECT count(*) FROM specialist_candidates WHERE state IN ("+WAITING+",'active')",Long.class);
            if(pending>=policy.pendingLimit())throw new InputRejected(429,"candidate_queue_full");
            db.update("UPDATE specialist_candidates SET state='ready',reason='manual_recovery',failures=0,revision=revision+1,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE candidate_id=?",id);
            var current=db.queryForList("SELECT *,lease_expires_at>clock_timestamp() AS alive FROM specialist_candidates WHERE candidate_id=?",id).getFirst();var response=view(current);
            db.update("INSERT INTO specialist_candidate_recoveries(request_id,candidate_id,actor_id,request_sha256,response) VALUES(?,?,?,?,?::jsonb)",body.requestId(),id,actor,encoded.sha256(),CanonicalJson.encode(response,16384).payload());
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'recover_specialist_candidate',?,?)",actor,body.reason().strip(),body.requestId());
            return response;
        });
    }
}
