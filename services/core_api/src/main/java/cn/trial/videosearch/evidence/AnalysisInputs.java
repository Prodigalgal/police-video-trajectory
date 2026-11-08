package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

/** Immutable, verified inputs. Saving one never produces an inference job. */
@Service
public class AnalysisInputs {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_MANIFEST=512*1024;
    private static final long MAX_IMAGES=64L*1024*1024;
    private final JdbcTemplate db;
    private final TemporalEvidence evidence;
    private final EvidenceFiles files;
    private final SearchOrchestrator search;
    private final TransactionTemplate transaction;
    public AnalysisInputs(JdbcTemplate db,TemporalEvidence evidence,EvidenceFiles files,SearchOrchestrator search,PlatformTransactionManager manager){
        this.db=db;this.evidence=evidence;this.files=files;this.search=search;transaction=new TransactionTemplate(manager);
    }
    public record Create(UUID requestId,UUID observationId,Long observationVersion,String evidenceSha256,String purpose,TemporalWindow window){}
    public record Verified(Map<String,Object> manifest,String payload,String sha256,EvidenceFiles.FrameFile image){}
    private record Snapshot(Map<String,Object> manifest,CanonicalJson.Encoded encoded,List<TemporalEvidence.Frame> frames){}

    public Map<String,Object> create(Create body,String actor){
        validate(body,actor);
        TemporalWindow window=body.purpose().equals("single_frame")?TemporalWindow.single():body.window()==null?TemporalWindow.defaults():body.window();
        if(body.purpose().equals("frame_sequence")&&(window.maxFrames()<2||window.maxGapMs()<1))throw new IllegalArgumentException("Sequence window is invalid");
        String fingerprint=CanonicalJson.encode(Map.of("observation_id",body.observationId().toString(),"observation_version",body.observationVersion(),
            "evidence_sha256",body.evidenceSha256(),"purpose",body.purpose(),"window",window.value()),16384).sha256();
        var anchor=evidence.anchor(body.observationId(),body.observationVersion());
        UUID previous=receipt(body.requestId(),actor,fingerprint);
        if(previous!=null)return read(previous);
        var context=body.purpose().equals("single_frame")
            ?new TemporalEvidence.Context(anchor,window,List.of(anchor.frame()),new TemporalWindow.Segment(List.of(anchor.frame().id()),"single_frame"),false,Set.of())
            :evidence.context(body.observationId(),body.observationVersion(),window);
        anchor=context.anchor();
        if(body.purpose().equals("frame_sequence")&&!context.segment().eligible())throw new InputRejected(422,"input_sequence_unavailable");
        Snapshot snapshot=snapshot(context,body);
        var frozenAnchor=anchor;
        UUID saved=transaction.execute(status->{
            lock("input-request:"+body.requestId());
            UUID replay=receipt(body.requestId(),actor,fingerprint);
            if(replay!=null)return replay;
            lock("input-content:"+actor+":"+snapshot.encoded().sha256());
            evidence.lockSources(frozenAnchor,snapshot.frames());evidence.unchanged(frozenAnchor,snapshot.frames());
            var duplicates=db.queryForList("SELECT input_id FROM analysis_inputs WHERE created_by=? AND manifest_sha256=?",actor,snapshot.encoded().sha256());
            UUID input=duplicates.isEmpty()?UUID.randomUUID():(UUID)duplicates.getFirst().get("input_id");
            if(duplicates.isEmpty())db.update("""
                INSERT INTO analysis_inputs(input_id,anchor_observation_id,anchor_record_version,anchor_frame_id,camera_id,purpose,created_by,manifest,manifest_sha256)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?)
                """,input,body.observationId(),body.observationVersion(),frozenAnchor.frame().id(),frozenAnchor.frame().camera(),body.purpose(),actor,snapshot.encoded().payload(),snapshot.encoded().sha256());
            db.update("INSERT INTO analysis_input_requests(request_id,actor_id,request_fingerprint,input_id) VALUES(?,?,?,?)",body.requestId(),actor,fingerprint,input);
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,"save_analysis_input","saved",body.requestId());
            return input;
        });
        return read(saved);
    }
    private Snapshot snapshot(TemporalEvidence.Context context,Create body){
        List<TemporalEvidence.Frame> frames=context.frames().stream().filter(f->context.segment().frameIds().contains(f.id())).toList();
        var verified=new ArrayList<Map<String,Object>>();long total=0;Map<String,Object> hint=Map.of("status","unavailable","reason","image_unavailable");
        for(var frame:frames){
            var image=readImage(frame);total+=image.bytes().length;
            if(total>MAX_IMAGES)throw new InputRejected(422,"input_too_large");
            if(frame.id().equals(context.anchor().frame().id())){
                if(!image.sha256().equals(body.evidenceSha256()))throw new InputRejected(409,"input_evidence_changed");
                hint=ObservationDetails.location(context.anchor().observation(),image);
            }
            var value=frame.view();value.put("payload_sha256",image.sha256());value.put("image_width",image.width());value.put("image_height",image.height());
            value.put("capture_integrity",frame.values().get("payload_sha256")==null?"legacy_without_digest":"matched");verified.add(value);
        }
        var manifest=new LinkedHashMap<String,Object>();
        manifest.put("schema_version","analysis-input-v1");manifest.put("purpose",body.purpose());
        manifest.put("input_semantics",body.purpose().equals("single_frame")?"image":"sampled_frame_sequence");
        manifest.put("anchor",context.anchor().view());manifest.put("anchor_fingerprint",context.anchor().fingerprint());
        var source=new LinkedHashMap<String,Object>();
        for(String key:List.of("camera_id","source_type","task_id","scene_run_id","media_epoch"))source.put(key,context.anchor().frame().view().get(key));
        source.put("clock_reference",context.anchor().frame().clockAvailable()?"capture_relative_media_ms":"unavailable");manifest.put("source",source);
        var selection=new LinkedHashMap<String,Object>(context.window().value());selection.put("truncated",context.truncated());
        selection.put("selected_start_ms",frames.getFirst().millis());selection.put("selected_end_ms",frames.getLast().millis());manifest.put("window",selection);
        manifest.put("frames",verified);manifest.put("target_hint",hint);
        manifest.put("quality",Map.of("continuous_video_verified",false,"identity_association_verified",false,"creation_invoked_inference",false));
        var encoded=CanonicalJson.encode(manifest,MAX_MANIFEST);return new Snapshot(manifest,encoded,frames);
    }
    private EvidenceFiles.FrameFile readImage(TemporalEvidence.Frame frame){
        EvidenceFiles.FrameFile image;
        try{image=files.read(frame.id(),frame.reference());}
        catch(IOException error){throw new InputRejected(422,"input_evidence_unavailable");}
        if(image==null)throw new InputRejected(422,"input_evidence_unavailable");
        Object registered=frame.values().get("payload_sha256");
        if(registered!=null){
            if(!image.sha256().equals(registered))throw new InputRejected(409,"input_evidence_changed");
            if(!Objects.equals(TemporalEvidence.dimension(frame.values().get("image_width")),image.width())||!Objects.equals(TemporalEvidence.dimension(frame.values().get("image_height")),image.height()))throw new InputRejected(409,"input_evidence_changed");
        }
        return image;
    }
    private static void validate(Create body,String actor){
        if(body==null||body.requestId()==null||body.observationId()==null||body.observationVersion()==null||body.observationVersion()<1||body.evidenceSha256()==null||!body.evidenceSha256().matches("[a-f0-9]{64}")
            ||!Set.of("single_frame","frame_sequence").contains(body.purpose()==null?"":body.purpose())||actor==null||actor.isBlank()||actor.length()>100)throw new IllegalArgumentException("Invalid input request");
    }
    private void lock(String value){db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",value);}
    private UUID receipt(UUID request,String actor,String fingerprint){
        var rows=db.queryForList("SELECT input_id,actor_id,request_fingerprint FROM analysis_input_requests WHERE request_id=?",request);
        if(rows.isEmpty())return null;var row=rows.getFirst();
        if(!actor.equals(row.get("actor_id"))||!fingerprint.equals(row.get("request_fingerprint")))throw new InputRejected(409,"input_request_conflict");
        return (UUID)row.get("input_id");
    }
    public Map<String,Object> list(UUID observation,String purpose,int limit,UUID after){
        if(observation==null||limit<1||limit>50||purpose!=null&&!Set.of("single_frame","frame_sequence").contains(purpose))throw new IllegalArgumentException("Invalid input page");
        evidence.anchor(observation,null);
        var parameters=new ArrayList<Object>();parameters.add(observation);
        var cameras=search.readableCameras();
        String filters=" WHERE i.anchor_observation_id=? AND i.camera_id IN ("+String.join(",",Collections.nCopies(cameras.size(),"?"))+")";
        parameters.addAll(cameras);
        if(purpose!=null){filters+=" AND i.purpose=?";parameters.add(purpose);}
        if(after!=null){filters+=" AND i.input_id>?";parameters.add(after);}
        parameters.add(limit+1);
        var rows=db.queryForList("SELECT i.input_id,i.anchor_observation_id,i.anchor_record_version,i.anchor_frame_id,i.camera_id,i.purpose,i.created_by,i.created_at,i.manifest_sha256,o.record_version AS current_version FROM analysis_inputs i JOIN observations o ON o.observation_id=i.anchor_observation_id"+filters+" ORDER BY i.input_id LIMIT ?",parameters.toArray());
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));
        var output=new LinkedHashMap<String,Object>();output.put("items",page.stream().map(AnalysisInputs::summary).toList());output.put("has_more",more);
        output.put("next_cursor",more?page.getLast().get("input_id").toString():null);return output;
    }
    public Map<String,Object> read(UUID id){return summary(stored(id));}
    private Map<String,Object> stored(UUID id){
        var rows=db.queryForList("""
            SELECT i.*,i.manifest::text AS manifest_text,o.record_version AS current_version FROM analysis_inputs i
            JOIN observations o ON o.observation_id=i.anchor_observation_id
            WHERE i.input_id=? AND NOT o.deleted
            """,id);
        if(rows.size()!=1||!search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"input_unavailable");
        // Read authorization also requires the observation's current source to remain in scope.
        evidence.anchor((UUID)rows.getFirst().get("anchor_observation_id"),null);return rows.getFirst();
    }
    static Map<String,Object> summary(Map<String,Object> row){
        var output=new LinkedHashMap<String,Object>();
        for(String key:List.of("input_id","anchor_observation_id","anchor_record_version","anchor_frame_id","camera_id","purpose","created_by","manifest_sha256"))output.put(key,row.get(key) instanceof UUID id?id.toString():row.get(key));
        output.put("created_at",((Timestamp)row.get("created_at")).toInstant().toString());
        output.put("source_status",Objects.equals(row.get("anchor_record_version"),row.get("current_version"))?"not_revalidated":"stale");
        output.put("creation_invoked_inference",false);return output;
    }
    public Verified verify(UUID id,UUID imageId){
        return verify(id,imageId,true);
    }
    public Verified metadata(UUID id){return verify(id,null,false);}
    public Verified lockForUse(UUID id,String expected){
        db.queryForList("SELECT input_id FROM analysis_inputs WHERE input_id=? FOR SHARE",id);
        var snapshot=metadata(id);if(!snapshot.sha256().equals(expected))throw new InputRejected(409,"input_manifest_changed");
        var row=stored(id);var anchor=evidence.anchor((UUID)row.get("anchor_observation_id"),((Number)row.get("anchor_record_version")).longValue());
        var identities=((List<?>)snapshot.manifest().get("frames")).stream().map(raw->UUID.fromString((String)((Map<?,?>)raw).get("frame_id"))).toList();
        evidence.lockSources(anchor,evidence.frames(identities));var current=metadata(id);
        if(!current.sha256().equals(expected))throw new InputRejected(409,"input_manifest_changed");return current;
    }
    @SuppressWarnings("unchecked")
    private Verified verify(UUID id,UUID imageId,boolean pixels){
        var row=stored(id);Map<String,Object> manifest;
        CanonicalJson.Encoded encoded;
        try{
            String payload=(String)row.get("manifest_text");if(payload.getBytes(StandardCharsets.UTF_8).length>MAX_MANIFEST)throw new IllegalArgumentException();
            manifest=JSON.readValue(payload,Map.class);encoded=CanonicalJson.encode(manifest,MAX_MANIFEST);
            if(!encoded.sha256().equals(row.get("manifest_sha256"))||!"analysis-input-v1".equals(manifest.get("schema_version")))throw new IllegalArgumentException();
        }catch(RuntimeException malformed){throw new InputRejected(409,"input_manifest_changed");}
        var anchor=evidence.anchor((UUID)row.get("anchor_observation_id"),((Number)row.get("anchor_record_version")).longValue());
        if(!CanonicalJson.encode(anchor.view(),16384).sha256().equals(CanonicalJson.encode(manifest.get("anchor"),16384).sha256())||!anchor.fingerprint().equals(manifest.get("anchor_fingerprint")))throw new InputRejected(409,"input_source_changed");
        if(!(manifest.get("frames") instanceof List<?> frames)||frames.isEmpty()||frames.size()>24)throw new InputRejected(409,"input_manifest_changed");
        var snapshots=new LinkedHashMap<UUID,Map<?,?>>();
        for(Object raw:frames){
            if(!(raw instanceof Map<?,?> saved))throw new InputRejected(409,"input_manifest_changed");
            UUID frameId;
            try{frameId=UUID.fromString((String)saved.get("frame_id"));}catch(RuntimeException malformed){throw new InputRejected(409,"input_manifest_changed");}
            if(snapshots.put(frameId,saved)!=null)throw new InputRejected(409,"input_manifest_changed");
        }
        if(!snapshots.containsKey(anchor.frame().id())||imageId!=null&&!snapshots.containsKey(imageId))throw new InputRejected(404,"input_frame_unavailable");
        var selected=evidence.frames(new ArrayList<>(snapshots.keySet()));EvidenceFiles.FrameFile requested=null;long total=0;
        for(var current:selected){
            Map<?,?> saved=snapshots.get(current.id());
            if(!current.fingerprint().equals(saved.get("source_fingerprint")))throw new InputRejected(409,"input_source_changed");
            if(!pixels)continue;
            // Manifest consumption verifies all pixels once. Each image read revalidates all
            // source metadata and its own pixels, avoiding N-squared sequence file reads.
            if(imageId!=null&&!current.id().equals(imageId))continue;
            var image=readImage(current);total+=image.bytes().length;
            if(total>MAX_IMAGES)throw new InputRejected(422,"input_too_large");
            if(!image.sha256().equals(saved.get("payload_sha256"))||!(saved.get("image_width") instanceof Number width)||width.intValue()!=image.width()
                ||!(saved.get("image_height") instanceof Number height)||height.intValue()!=image.height())throw new InputRejected(409,"input_evidence_changed");
            if(current.id().equals(imageId))requested=image;
        }
        evidence.unchanged(anchor,selected);
        return new Verified(manifest,encoded.payload(),encoded.sha256(),requested);
    }
}
