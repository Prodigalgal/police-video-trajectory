package cn.trial.videosearch.investigation;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.ingest.ObservationStore;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Bounded 1:1 projections; never loads image payloads or an unbounded observation history. */
@Repository
public class InvestigationRepository {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SELECT="""
        SELECT o.observation_id,o.record_version,o.frame_id,o.camera_id,cam.display_name,o.target_type,o.description,
        o.attributes::text AS attributes_text,o.uncertain_fields::text AS uncertain_text,f.task_id,
        CASE WHEN f.source_time_origin='verified' THEN f.source_time END AS source_time,
        c.scene_run_id,c.media_epoch,c.relative_media_ms,c.payload_sha256,d.track_id,d.target_id,d.class_name,
        CASE WHEN g.coordinate_source='configured' THEN g.longitude END AS longitude,
        CASE WHEN g.coordinate_source='configured' THEN g.latitude END AS latitude
        FROM observations o JOIN frames f USING(frame_id) JOIN processing_tasks t ON t.task_id=f.task_id
        JOIN sources s ON s.source_id=t.source_id JOIN cameras cam ON cam.camera_id=o.camera_id
        LEFT JOIN capture_outbox c ON c.frame_id=o.frame_id
        LEFT JOIN detected_targets d ON d.frame_id=o.frame_id AND d.observation_ordinal=o.object_ordinal AND d.object_type=o.target_type
        LEFT JOIN camera_locations g ON g.camera_id=o.camera_id
        WHERE NOT o.deleted AND f.analysis_state='completed' AND o.camera_id=s.camera_id
        """;
    private final JdbcTemplate db;
    private final SearchOrchestrator scope;
    public InvestigationRepository(JdbcTemplate db,SearchOrchestrator scope){this.db=db;this.scope=scope;}
    public ObservationPoint anchor(UUID id,Long expected){
        var rows=db.queryForList(SELECT+" AND o.observation_id=?",id);
        if(rows.size()!=1||!scope.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new AdminService.Rejected(404,"observation_unavailable");
        var point=point(rows.getFirst());
        if(expected!=null&&expected!=point.version())throw new AdminService.Rejected(409,"observation_revision_conflict");return point;
    }
    public List<ObservationPoint> nearby(ObservationPoint anchor,int before,int after,int limit){
        if(!anchor.localClock())return List.of(anchor);
        long start=Math.max(0,anchor.mediaMs()-before),end=Math.addExact(anchor.mediaMs(),after);
        return db.queryForList(SELECT+" AND o.camera_id=? AND f.task_id=? AND c.scene_run_id=? AND c.media_epoch=? AND c.relative_media_ms BETWEEN ? AND ? AND o.target_type=? ORDER BY abs(c.relative_media_ms-?),o.observation_id LIMIT ?",anchor.cameraId(),anchor.taskId(),anchor.runId(),anchor.epoch(),start,end,anchor.targetType(),anchor.mediaMs(),limit).stream().map(InvestigationRepository::point).toList();
    }
    public List<ObservationPoint> otherCameras(ObservationPoint anchor,int seconds,int limit){
        var cameras=scope.readableCameras().stream().filter(camera->!camera.equals(anchor.cameraId())).sorted().toList();
        if(cameras.isEmpty()||anchor.sourceTime()==null)return List.of();
        var args=new ArrayList<Object>(cameras);args.add(anchor.targetType());args.add(Timestamp.from(anchor.sourceTime().minusSeconds(seconds)));args.add(Timestamp.from(anchor.sourceTime().plusSeconds(seconds)));args.add(Timestamp.from(anchor.sourceTime()));args.add(limit);
        return db.queryForList(SELECT+" AND o.camera_id IN ("+String.join(",",Collections.nCopies(cameras.size(),"?"))+") AND o.target_type=? AND f.source_time_origin='verified' AND f.source_time BETWEEN ? AND ? ORDER BY abs(extract(epoch FROM f.source_time-?::timestamptz)),o.observation_id LIMIT ?",args.toArray()).stream().map(InvestigationRepository::point).toList();
    }
    @SuppressWarnings("unchecked") private static ObservationPoint point(Map<String,Object> row){
        Map<String,Object> attributes=ObservationStore.indexedAttributes(JSON.readValue((String)row.get("attributes_text"),Map.class),JSON.readValue((String)row.get("uncertain_text"),List.class));
        return new ObservationPoint((UUID)row.get("observation_id"),((Number)row.get("record_version")).longValue(),(UUID)row.get("frame_id"),(String)row.get("camera_id"),(String)row.get("display_name"),(String)row.get("target_type"),(String)row.get("description"),attributes,(UUID)row.get("task_id"),(UUID)row.get("scene_run_id"),number(row.get("media_epoch")),number(row.get("relative_media_ms")),row.get("source_time") instanceof Timestamp time?time.toInstant():null,(UUID)row.get("track_id"),(UUID)row.get("target_id"),(String)row.get("class_name"),(String)row.get("payload_sha256"),decimal(row.get("longitude")),decimal(row.get("latitude")));
    }
    private static Long number(Object value){return value instanceof Number n?n.longValue():null;}
    private static Double decimal(Object value){return value instanceof Number n?n.doubleValue():null;}
}
