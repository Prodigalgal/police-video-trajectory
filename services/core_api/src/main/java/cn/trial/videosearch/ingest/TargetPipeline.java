package cn.trial.videosearch.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.*;

/** Fenced capture registration, bounded dispatch and target-bound module completion. */
@Service
public class TargetPipeline {
    public record Capture(UUID frameId, UUID taskId, String workerId, long generation, String evidenceRef,
                          String payloadSha256, Long mediaOffsetMs, Instant sourceTime, UUID sceneRunId,
                          long mediaEpoch, long relativeMediaMs, Map<String,Object> plan) {}
    public record Receipt(UUID frameId, UUID receipt, String payloadSha256) {}
    public record Activation(UUID frameId, UUID receipt, String payloadSha256) {}
    public record TargetResult(UUID targetId, String objectType, Boolean visible, Map<String,Object> attributes,
                               String description, List<String> uncertainFields) {}
    public record Completion(UUID jobId, String workerId, long generation, String modelId, String promptVersion,
                             List<TargetResult> targets, List<ObservationStore.VisibleObject> additionalObjects, String sceneDescription) {}
    private final JdbcTemplate db;
    private final AnalysisQueue queue;
    private final ObservationStore observations;
    private final ModuleQueue modules;
    private final ObjectMapper json = new ObjectMapper();
    private final int captureLimit;
    private final Path evidenceRoot;

    public TargetPipeline(JdbcTemplate db, AnalysisQueue queue, ObservationStore observations,
                          @Value("${VIDEO_CAPTURE_OUTBOX_LIMIT:2000}") int captureLimit,
                          @Value("${VIDEO_EVIDENCE_DIR:/evidence}") String evidenceRoot,ModuleQueue modules) {
        if(captureLimit<1 || captureLimit>10000) throw new IllegalArgumentException("invalid capture limit");
        this.db=db; this.queue=queue; this.observations=observations; this.captureLimit=captureLimit;
        this.evidenceRoot=Path.of(evidenceRoot).toAbsolutePath().normalize();
        this.modules=modules;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> targets(Map<String,Object> plan) {
        if(plan==null || !"targets-v1".equals(plan.get("version")) || !"frame_batch".equals(plan.get("strategy")) ||
                !(plan.get("detection_state") instanceof String state) || !Set.of("complete","unknown").contains(state) ||
                !(plan.get("targets") instanceof List<?> raw) || raw.size()>100)
            throw new IllegalArgumentException("invalid target plan");
        double width=number(plan.get("image_width")), height=number(plan.get("image_height"));
        if(width<1 || height<1 || width>8192 || height>8192 || width%1!=0 || height%1!=0) throw new IllegalArgumentException("invalid image geometry");
        var ids=new HashSet<UUID>();
        for(Object item:raw) {
            if(!(item instanceof Map<?,?> target)) throw new IllegalArgumentException("invalid target");
            if(!(target.get("target_id") instanceof String id) || !(target.get("track_id") instanceof String track) ||
                    !(target.get("object_type") instanceof String kind) || !(target.get("class_name") instanceof String name))
                throw new IllegalArgumentException("invalid target identity");
            if(!ids.add(UUID.fromString(id))) throw new IllegalArgumentException("duplicate target");
            UUID.fromString(track);
            if(!Set.of("person","bicycle","car","motorcycle","bus","truck").contains(name) ||
                    !("person".equals(name)?"person":"vehicle").equals(kind) ||
                    !"local_geometry_candidate".equals(target.get("association"))) throw new IllegalArgumentException("invalid target class");
            double score=number(target.get("confidence"));
            if(score<0 || score>1) throw new IllegalArgumentException("invalid score");
            if(!(target.get("bbox_xyxy") instanceof List<?> box) || !(target.get("bbox_normalized") instanceof List<?> normalized) ||
                    box.size()!=4 || normalized.size()!=4) throw new IllegalArgumentException("invalid box");
            var values=box.stream().map(TargetPipeline::number).toList();
            ObservationStore.validateBoundingBox(values);
            if(values.get(2)>width || values.get(3)>height) throw new IllegalArgumentException("box outside image");
            for(int i=0;i<4;i++) if(Math.abs(number(normalized.get(i))-values.get(i)/(i%2==0?width:height))>0.0001)
                throw new IllegalArgumentException("inconsistent box units");
        }
        return (List<Map<String,Object>>)raw;
    }
    static double number(Object value) {
        if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("invalid number");
        return n.doubleValue();
    }

    @Transactional
    public Receipt register(Capture item) {
        if(item==null || item.frameId()==null || item.taskId()==null || item.sceneRunId()==null || item.generation()<1 ||
                item.mediaEpoch()<0 || item.relativeMediaMs()<0 || item.workerId()==null || !item.workerId().matches("[A-Za-z0-9_.-]{1,100}") ||
                item.payloadSha256()==null || !item.payloadSha256().matches("[0-9a-f]{64}") ||
                !Objects.equals(item.evidenceRef(), "evidence:"+item.frameId().toString().replace("-","")) ||
                (item.mediaOffsetMs()!=null && item.mediaOffsetMs()<0)) throw new IllegalArgumentException("invalid capture");
        var targets=targets(item.plan());
        verifyEvidence(item.frameId(),item.payloadSha256());
        String encoded=json.writeValueAsString(item.plan());
        if(encoded.length()>256000) throw new IllegalArgumentException("plan too large");
        // Acknowledged, immutable captures can be replayed after source renewal.
        // New captures always require the original live source lease.
        db.execute("SELECT pg_advisory_xact_lock(918273646)");
        var old=db.queryForList("SELECT receipt FROM capture_outbox WHERE frame_id=? AND task_id=? AND worker_id=? AND lease_generation=? AND evidence_ref=? AND payload_sha256=? AND media_offset_ms IS NOT DISTINCT FROM ? AND source_time IS NOT DISTINCT FROM ? AND scene_run_id=? AND media_epoch=? AND relative_media_ms=? AND plan=CAST(? AS jsonb)",
                item.frameId(),item.taskId(),item.workerId(),item.generation(),item.evidenceRef(),item.payloadSha256(),item.mediaOffsetMs(),item.sourceTime()==null?null:Timestamp.from(item.sourceTime()),item.sceneRunId(),item.mediaEpoch(),item.relativeMediaMs(),encoded);
        if(!old.isEmpty()) return new Receipt(item.frameId(),(UUID)old.getFirst().get("receipt"),item.payloadSha256());
        Integer exists=db.queryForObject("SELECT count(*) FROM capture_outbox WHERE frame_id=?",Integer.class,item.frameId());
        if(exists!=null && exists!=0) throw new IllegalStateException("capture replay conflict");
        if(!Objects.equals(0,db.queryForObject("SELECT count(*) FROM frames WHERE frame_id=?",Integer.class,item.frameId())))
            throw new IllegalStateException("frame already belongs to another pipeline");
        var lease=db.queryForList("""
                SELECT s.camera_id,s.source_type,s.source_time_origin FROM processing_tasks t JOIN sources s ON s.source_id=t.source_id
                JOIN cameras c ON c.camera_id=s.camera_id WHERE t.task_id=? AND t.state='active' AND t.lease_owner=?
                AND t.lease_generation=? AND t.lease_expires_at>clock_timestamp() AND s.enabled AND c.enabled FOR UPDATE OF t
                """,item.taskId(),item.workerId(),item.generation());
        if(lease.size()!=1) throw new IllegalStateException("capture source lease expired");
        var row=lease.getFirst();
        if(!"verified".equals(row.get("source_time_origin")) && item.sourceTime()!=null) throw new IllegalArgumentException("unverified capture clock");
        Integer pending=db.queryForObject("SELECT count(*) FROM capture_outbox WHERE enqueued_at IS NULL",Integer.class);
        if(pending==null || pending>=captureLimit) throw new IllegalStateException("capture outbox full");
        UUID receipt=UUID.randomUUID();
        db.update("""
                INSERT INTO capture_outbox(frame_id,receipt,task_id,worker_id,lease_generation,evidence_ref,payload_sha256,camera_id,source_type,
                  source_time_origin,media_offset_ms,source_time,scene_run_id,media_epoch,relative_media_ms,plan)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """,item.frameId(),receipt,item.taskId(),item.workerId(),item.generation(),item.evidenceRef(),item.payloadSha256(),row.get("camera_id"),row.get("source_type"),row.get("source_time_origin"),
                item.mediaOffsetMs(),item.sourceTime()==null?null:Timestamp.from(item.sourceTime()),item.sceneRunId(),item.mediaEpoch(),item.relativeMediaMs(),encoded);
        int ordinal=0;
        for(var target:targets) {
            db.update("""
                    INSERT INTO detected_targets(target_id,frame_id,target_ordinal,object_type,class_name,confidence,bbox_xyxy,bbox_normalized,track_id,association)
                    VALUES(?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)
                    """,UUID.fromString((String)target.get("target_id")),item.frameId(),ordinal++,target.get("object_type"),target.get("class_name"),number(target.get("confidence")),
                    json.writeValueAsString(target.get("bbox_xyxy")),json.writeValueAsString(target.get("bbox_normalized")),UUID.fromString((String)target.get("track_id")),target.get("association"));
        }
        // Derive dispatch on the server; do not trust caller-supplied module IDs.
        for(String kind:List.of("person","vehicle")) {
            var ids=targets.stream().filter(t->kind.equals(t.get("object_type"))).map(t->t.get("target_id")).toList();
            if(!ids.isEmpty()) db.update("INSERT INTO analysis_modules(frame_id,module_key,target_ids) VALUES(?,?,CAST(? AS jsonb))",item.frameId(),kind+"_attributes",json.writeValueAsString(ids));
        }
        db.update("INSERT INTO analysis_modules(frame_id,module_key,target_ids) VALUES(?,'scene_context','[]'::jsonb)",item.frameId());
        int live=db.update("UPDATE processing_tasks SET updated_at=clock_timestamp() WHERE task_id=? AND lease_owner=? AND lease_generation=? AND state='active' AND lease_expires_at>clock_timestamp()",item.taskId(),item.workerId(),item.generation());
        if(live!=1) throw new IllegalStateException("capture lease expired before commit");
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'capture.register','committed',?)",item.workerId(),item.frameId());
        return new Receipt(item.frameId(),receipt,item.payloadSha256());
    }

    @Transactional
    public Optional<UUID> activate(Activation item) {
        if(item==null || item.frameId()==null || item.receipt()==null || item.payloadSha256()==null) throw new IllegalArgumentException("invalid activation");
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        var rows=db.queryForList("SELECT * FROM capture_outbox WHERE frame_id=? AND receipt=? AND payload_sha256=? FOR UPDATE",item.frameId(),item.receipt(),item.payloadSha256());
        if(rows.size()!=1) throw new IllegalStateException("capture receipt conflict");
        if(rows.getFirst().get("enqueued_at")==null) admitWaiting(8);
        var jobs=db.queryForList("SELECT job_id FROM analysis_jobs WHERE frame_id=?",item.frameId());
        return jobs.isEmpty()?Optional.empty():Optional.of((UUID)jobs.getFirst().get("job_id"));
    }

    /** Registered facts, not competing client retries, determine admission order. */
    @Transactional
    public int dispatchWaiting() {
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        return admitWaiting(8);
    }

    private int admitWaiting(int batchLimit) {
        Integer pending=db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE state IN ('queued','active')",Integer.class);
        if(pending==null || pending>=queue.pendingLimit()) return 0;
        var waiting=db.queryForList("SELECT c.* FROM capture_outbox c WHERE c.enqueued_at IS NULL AND NOT EXISTS(SELECT 1 FROM capture_faults q WHERE q.frame_id=c.frame_id AND q.restored_at IS NULL) ORDER BY c.registered_at,c.frame_id FOR UPDATE OF c SKIP LOCKED LIMIT ?",Math.min(batchLimit,queue.pendingLimit()-pending));
        int admitted=0;
        for(var row:waiting) {
            UUID frameId=(UUID)row.get("frame_id");
            try { verifyEvidence(frameId,(String)row.get("payload_sha256")); }
            catch(CaptureEvidence.Fault error) {
                db.update("""
                    INSERT INTO capture_faults(frame_id,error_code) VALUES(?,?)
                    ON CONFLICT(frame_id) DO UPDATE SET error_code=excluded.error_code,revision=capture_faults.revision+1,
                      detected_at=clock_timestamp(),restored_at=NULL
                    """,frameId,error.code());
                db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES('capture-dispatcher','capture.quarantine',?,?)",error.code(),frameId);
                continue;
            }
            UUID jobId=UUID.nameUUIDFromBytes(("analysis:"+frameId).getBytes(StandardCharsets.UTF_8));
            db.update("""
                    INSERT INTO frames(frame_id,task_id,lease_generation,evidence_ref,media_offset_ms,source_time,source_time_origin,received_at,analysis_state)
                    VALUES(?,?,?,?,?,?,?,clock_timestamp(),'queued')
                    """,frameId,row.get("task_id"),row.get("lease_generation"),row.get("evidence_ref"),row.get("media_offset_ms"),row.get("source_time"),row.get("source_time_origin"));
            db.update("INSERT INTO analysis_jobs(job_id,frame_id,state) VALUES(?,?,'queued')",jobId,frameId);
            db.update("UPDATE capture_outbox SET enqueued_at=clock_timestamp() WHERE frame_id=?",frameId);
            admitted++;
        }
        return admitted;
    }

    private void verifyEvidence(UUID frameId,String expectedDigest) {
        new CaptureEvidence(evidenceRoot.toString()).verify(frameId,expectedDigest);
    }

    public Map<String,Object> job(AnalysisQueue.Job job) {
        var result=new LinkedHashMap<String,Object>();
        result.put("jobId",job.jobId()); result.put("frameId",job.frameId()); result.put("evidenceRef",job.evidenceRef());
        result.put("attempts",job.attempts()); result.put("generation",job.generation()); result.put("expiresAt",job.expiresAt());
        var plans=db.queryForList("SELECT plan::text FROM capture_outbox WHERE frame_id=?",job.frameId());
        if(!plans.isEmpty()) result.put("targetPlan",json.readValue((String)plans.getFirst().get("plan"),Map.class));
        return result;
    }

    @Transactional
    public Optional<Map<String,Object>> claim(String worker,int seconds){
        if(worker==null || !worker.matches("[A-Za-z0-9_.-]{1,100}") || seconds<1 || seconds>600)throw new IllegalArgumentException("invalid analysis lease");
        modules.lockAdmission();
        if(!modules.hasFrameCapacity())return Optional.empty();
        return queue.claimTargets(worker,seconds).map(this::job);
    }

    @Transactional
    public int complete(Completion item) {
        if(item==null || item.targets()==null || item.targets().size()>100 || item.additionalObjects()==null || item.additionalObjects().size()>100 ||
                (item.sceneDescription()!=null && item.sceneDescription().length()>500)) throw new IllegalArgumentException("invalid module result");
        modules.lockAdmission();
        var rows=db.queryForList("SELECT f.frame_id,(c.plan->>'image_width')::integer AS image_width,(c.plan->>'image_height')::integer AS image_height FROM analysis_jobs j JOIN frames f ON f.frame_id=j.frame_id JOIN capture_outbox c ON c.frame_id=f.frame_id WHERE j.job_id=? AND j.lease_owner=? AND j.lease_generation=? AND ((j.state='active' AND j.lease_expires_at>clock_timestamp()) OR j.state='completed') FOR UPDATE OF j",item.jobId(),item.workerId(),item.generation());
        if(rows.size()!=1) throw new IllegalStateException("module lease expired");
        UUID frameId=(UUID)rows.getFirst().get("frame_id");
        var expected=db.queryForList("SELECT target_id,object_type,bbox_xyxy::text FROM detected_targets WHERE frame_id=? ORDER BY target_ordinal",frameId);
        var byId=new HashMap<UUID,TargetResult>();
        for(var target:item.targets()) {
            if(target==null || target.targetId()==null || byId.put(target.targetId(),target)!=null) throw new IllegalArgumentException("duplicate target result");
            ObservationStore.validateObject(new ObservationStore.VisibleObject(target.objectType(),target.attributes(),target.description(),null,target.uncertainFields()));
        }
        if(byId.size()!=expected.size()) throw new IllegalArgumentException("missing target results");
        var objects=new ArrayList<ObservationStore.VisibleObject>();
        db.update("UPDATE detected_targets SET observation_ordinal=NULL WHERE frame_id=?",frameId);
        for(var target:expected) {
            UUID targetId=(UUID)target.get("target_id");
            var value=byId.remove(targetId);
            if(value==null || !Objects.equals(value.objectType(),target.get("object_type"))) throw new IllegalArgumentException("unbound target result");
            if(Boolean.TRUE.equals(value.visible())) {
                var box=json.readValue((String)target.get("bbox_xyxy"),List.class).stream().map(TargetPipeline::number).toList();
                db.update("UPDATE detected_targets SET observation_ordinal=? WHERE target_id=?",objects.size(),targetId);
                objects.add(new ObservationStore.VisibleObject(value.objectType(),value.attributes(),value.description(),box,value.uncertainFields()));
            }
        }
        for(var extra:item.additionalObjects()) {
            ObservationStore.validateObject(extra);
            if(extra.bboxXyxy()==null || extra.bboxXyxy().get(2)>number(rows.getFirst().get("image_width")) ||
                    extra.bboxXyxy().get(3)>number(rows.getFirst().get("image_height"))) throw new IllegalArgumentException("additional object outside image");
            objects.add(extra);
        }
        if(objects.size()>100) throw new IllegalArgumentException("too many output objects");
        int count=observations.completeBound(new ObservationStore.AnalysisResult(item.jobId(),item.workerId(),item.generation(),item.modelId(),item.promptVersion(),objects));
        modules.enqueue(frameId,item);
        return count;
    }

    @Transactional
    public boolean controlClaims(Boolean enabled, String actor) {
        if(enabled==null || actor==null || !actor.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("invalid processing control");
        db.execute("SELECT pg_advisory_xact_lock(918273645)");
        db.update("UPDATE processing_controls SET enabled=?,updated_at=clock_timestamp() WHERE control_key='analysis_claims'",enabled);
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'analysis.claims.control',?,?)",actor,enabled?"enabled":"draining",UUID.randomUUID());
        return enabled;
    }

    public Map<String,Object> status() {
        return Map.of("claims_enabled",db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class),
                "capture_limit",captureLimit,"waiting_captures",db.queryForObject("SELECT count(*) FROM capture_outbox WHERE enqueued_at IS NULL",Integer.class),
                "targets",db.queryForObject("SELECT count(*) FROM detected_targets",Integer.class),
                "module_execution","independent_saved_frame_result_with_legacy_records","module_policy",modules.policy(),"independent_jobs",modules.status(),
                "modules",db.queryForList("""
                    SELECT module_key,state,parent_state,count(*) AS count FROM (
                      SELECT m.module_key,j.state AS parent_state,
                        CASE WHEN (m.result IS NULL)<>(m.completed_at IS NULL) THEN 'inconsistent'
                             WHEN r.job_id IS NOT NULL AND ((r.state='completed')<>(m.result IS NOT NULL)) THEN 'inconsistent'
                             WHEN m.result IS NOT NULL THEN 'completed'
                             WHEN r.state='active' AND (r.lease_expires_at IS NULL OR r.lease_expires_at<=clock_timestamp()) THEN 'lease_expired'
                             WHEN r.state='queued' AND r.next_attempt_at>clock_timestamp() THEN 'retry_wait'
                             WHEN r.job_id IS NOT NULL THEN r.state
                             WHEN j.state IS NULL THEN 'awaiting_capacity'
                             WHEN j.state='completed' THEN 'inconsistent'
                             WHEN j.state='active' AND (j.lease_expires_at IS NULL OR j.lease_expires_at<=clock_timestamp()) THEN 'lease_expired'
                             WHEN j.state='queued' AND j.next_attempt_at>clock_timestamp() THEN 'retry_wait'
                             ELSE j.state END AS state
                      FROM analysis_modules m LEFT JOIN analysis_jobs j ON j.frame_id=m.frame_id
                      LEFT JOIN analysis_module_jobs r ON r.frame_id=m.frame_id AND r.module_key=m.module_key
                    ) states GROUP BY module_key,state,parent_state ORDER BY module_key,state,parent_state
                    """));
    }
}
