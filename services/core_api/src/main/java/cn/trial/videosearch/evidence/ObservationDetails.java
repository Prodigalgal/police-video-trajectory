package cn.trial.videosearch.evidence;

import cn.trial.videosearch.ingest.ObservationStore;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;

/** Authorized business evidence view. No source URL, credentials or training data. */
@Service
public class ObservationDetails {
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private final EvidenceFiles files;
    private final ReviewedPlateFacts plates;
    private final Path archives;
    private static final ObjectMapper JSON=new ObjectMapper();
    public ObservationDetails(JdbcTemplate db,SearchOrchestrator search,EvidenceFiles files,@Value("${VIDEO_ARCHIVE_DIR:/recordings}") String archives,ReviewedPlateFacts plates) {
        this.db=db;this.search=search;this.files=files;this.plates=plates;this.archives=Path.of(archives).toAbsolutePath().normalize();
    }
    @SuppressWarnings("unchecked")
    public Optional<Map<String,Object>> read(UUID id) throws Exception {
        String outcome="not_found";UUID request=UUID.randomUUID();
        try {
            var rows=db.queryForList("""
                    SELECT o.observation_id,o.frame_id,o.camera_id,o.target_type,o.object_ordinal,
                      o.attributes::text AS attributes_text,o.uncertain_fields::text AS uncertain_text,
                      o.bbox_xyxy::text AS observation_bbox,o.description,o.model_id,o.prompt_version,o.record_version,
                      f.evidence_ref,f.source_time,f.received_at,f.media_offset_ms,f.source_time_origin,
                      s.source_type,s.source_ref,cam.display_name,
                      c.payload_sha256,c.plan::text AS plan_text,
                      d.target_id,d.object_type AS detector_object_type,d.class_name,d.bbox_xyxy::text AS detector_bbox
                    FROM observations o JOIN frames f ON f.frame_id=o.frame_id
                    JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id
                    JOIN cameras cam ON cam.camera_id=o.camera_id
                    LEFT JOIN capture_outbox c ON c.frame_id=f.frame_id
                    LEFT JOIN detected_targets d ON d.frame_id=f.frame_id AND d.observation_ordinal=o.object_ordinal
                    WHERE o.observation_id=? AND NOT o.deleted AND f.analysis_state='completed' AND o.camera_id=s.camera_id
                    """,id);
            if(rows.size()!=1 || !search.mayReadCamera((String)rows.getFirst().get("camera_id"))) return Optional.empty();
            var row=rows.getFirst();UUID frame=(UUID)row.get("frame_id");
            var result=new LinkedHashMap<String,Object>();
            for(String key:List.of("observation_id","frame_id","camera_id","target_type","description","model_id","prompt_version","record_version","source_type","source_time_origin","media_offset_ms")) result.put(key,row.get(key));
            result.put("camera_name",row.get("display_name"));
            if(plates.enabled())result.put("reviewed_plate",plates.details(id,((Number)row.get("record_version")).longValue()));
            for(String key:List.of("source_time","received_at")) result.put(key,row.get(key) instanceof Timestamp time?time.toInstant().toString():null);
            Map<String,Object> attributes=JSON.readValue((String)row.get("attributes_text"),Map.class);
            List<String> uncertain=JSON.readValue((String)row.get("uncertain_text"),List.class);
            result.put("attributes",ObservationStore.indexedAttributes(attributes,uncertain));result.put("uncertain_fields",uncertain);
            EvidenceFiles.FrameFile image;
            try { image=files.read(frame,(String)row.get("evidence_ref")); } catch(java.io.IOException unavailable) { image=null; }
            var evidence=new LinkedHashMap<String,Object>();evidence.put("available",image!=null);
            if(image!=null) {
                evidence.put("width",image.width());evidence.put("height",image.height());evidence.put("bytes",image.bytes().length);evidence.put("sha256",image.sha256());
                evidence.put("capture_integrity",row.get("payload_sha256")==null?"legacy_without_digest":image.sha256().equals(row.get("payload_sha256"))?"matched":"mismatch");
            }
            result.put("evidence",evidence);result.put("target_location",location(row,image));
            boolean video=false;String ref=(String)row.get("source_ref");
            if("archive".equals(row.get("source_type")) && ref!=null && ref.matches("archive:recording-0[1-6]")) {
                Path path=archives.resolve(ref.substring("archive:".length())+".mp4").normalize();
                video=path.getParent().equals(archives) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.size(path)>0;
            }
            result.put("video",Map.of("available",video,"reason",video?"registered_archive":"archive_unavailable"));
            outcome="ok";return Optional.of(result);
        } finally { db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)","trial-service-account","read_observation",outcome,request); }
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> location(Map<String,Object> row,EvidenceFiles.FrameFile image) {
        if(image==null) return unavailable("image_unavailable");
        if(row.get("target_id")==null || row.get("plan_text")==null) return unavailable("no_detector_binding");
        if(!image.sha256().equals(row.get("payload_sha256"))) return unavailable("capture_digest_mismatch");
        try {
            var plan=JSON.readValue((String)row.get("plan_text"),Map.class);
            if(!(plan.get("image_width") instanceof Number width) || width.doubleValue()!=image.width()
                    || !(plan.get("image_height") instanceof Number height) || height.doubleValue()!=image.height()) return unavailable("image_dimensions_mismatch");
            if(!Objects.equals(row.get("target_type"),row.get("detector_object_type"))) return unavailable("target_type_mismatch");
            var box=JSON.readValue((String)row.get("detector_bbox"),List.class);
            var observed=JSON.readValue((String)row.get("observation_bbox"),List.class);
            if(box.size()!=4 || observed.size()!=4) return unavailable("invalid_box");
            var values=new ArrayList<Double>();
            for(int i=0;i<4;i++) {
                if(!(box.get(i) instanceof Number a) || !(observed.get(i) instanceof Number b) || !Double.isFinite(a.doubleValue()) || !Double.isFinite(b.doubleValue()) || Math.abs(a.doubleValue()-b.doubleValue())>0.000001) return unavailable("box_binding_mismatch");
                values.add(a.doubleValue());
            }
            if(values.get(0)<0 || values.get(1)<0 || values.get(2)>image.width() || values.get(3)>image.height()
                    || values.get(0)>=values.get(2) || values.get(1)>=values.get(3)) return unavailable("invalid_box");
            return Map.of("status","bound_detection","bbox_xyxy",values,"image_width",image.width(),"image_height",image.height(),
                    "target_id",row.get("target_id").toString(),"class_name",row.get("class_name"),"coordinate_space","jpeg_pixels");
        } catch(Exception malformed) { return unavailable("invalid_binding"); }
    }
    private static Map<String,Object> unavailable(String reason) { return Map.of("status","unavailable","reason",reason); }
}
