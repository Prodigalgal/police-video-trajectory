package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

/** Read model for retained precheck captures, separate from semantic observations. */
@Service
public class RecordingReviewQueries {
    private final JdbcTemplate db;
    private final SearchOrchestrator scope;
    private final FrameRecordings recordings;
    private final EvidenceFiles images;
    private final boolean enabled;
    private static final String JOINS=" FROM frame_recording_links l JOIN capture_outbox c USING(frame_id) JOIN stream_recording_segments r USING(segment_id) JOIN cameras m ON m.camera_id=c.camera_id";
    private static final String VISIBLE=" AND (NOT EXISTS(SELECT 1 FROM observations o WHERE o.frame_id=c.frame_id) OR EXISTS(SELECT 1 FROM observations o WHERE o.frame_id=c.frame_id AND NOT o.deleted))";
    private static final String COLUMNS="SELECT c.frame_id,c.camera_id,m.display_name,c.source_type,c.registered_at,c.enqueued_at,c.admission_requested,c.payload_sha256,c.evidence_ref,jsonb_array_length(c.plan->'targets') AS target_count,l.segment_id,l.seek_offset_ms,r.manifest_sha256,(r.manifest->>'duration_us')::bigint AS duration_us,(r.manifest->'video'->>'bytes')::bigint AS video_bytes";
    public RecordingReviewQueries(JdbcTemplate db,SearchOrchestrator scope,FrameRecordings recordings,EvidenceFiles images,@Value("${VIDEO_RECORDING_REVIEW_ENABLED:false}")boolean enabled){this.db=db;this.scope=scope;this.recordings=recordings;this.images=images;this.enabled=enabled;}
    private record Filter(String sql,List<Object> values){}
    private Filter filter(String camera){
        if(!enabled)throw new InputRejected(404,"recording_review_disabled");
        var cameras=scope.readableCameras().stream().sorted().toList();
        if(camera!=null&&(!camera.matches("[A-Za-z0-9_-]{1,100}")||!cameras.contains(camera)))throw new InputRejected(404,"recording_unavailable");
        if(cameras.isEmpty())return new Filter(" WHERE false",List.of());
        var values=new ArrayList<Object>(cameras);
        String sql=" WHERE m.enabled AND c.camera_id IN ("+String.join(",",Collections.nCopies(cameras.size(),"?"))+")"+VISIBLE;
        if(camera!=null){sql+=" AND c.camera_id=?";values.add(camera);}
        return new Filter(sql,values);
    }
    public Map<String,Object> list(String camera,int limit,UUID after){
        if(limit<1||limit>50)throw new IllegalArgumentException("bounded recording page required");
        var filter=filter(camera);var values=new ArrayList<>(filter.values());String paging="";
        if(after!=null){
            var args=new ArrayList<>(values);args.add(after);
            var anchor=db.queryForList("SELECT c.registered_at,c.frame_id"+JOINS+filter.sql()+" AND c.frame_id=?",args.toArray());
            if(anchor.size()!=1)throw new InputRejected(400,"recording_cursor_invalid");
            paging=" AND (c.registered_at,c.frame_id)<(?,?)";values.add(anchor.getFirst().get("registered_at"));values.add(after);
        }
        values.add(limit+1);
        var rows=db.queryForList(COLUMNS+JOINS+filter.sql()+paging+" ORDER BY c.registered_at DESC,c.frame_id DESC LIMIT ?",values.toArray());
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size())).stream().map(RecordingReviewQueries::view).toList();
        var totals=db.queryForMap("SELECT count(*) AS retained_frames,count(DISTINCT l.segment_id) AS retained_segments"+JOINS+filter.sql(),filter.values().toArray());
        Long bytes=db.queryForObject("SELECT COALESCE(sum(retained.bytes),0)::bigint FROM (SELECT DISTINCT r.segment_id,(r.manifest->'video'->>'bytes')::bigint AS bytes"+JOINS+filter.sql()+") retained",Long.class,filter.values().toArray());
        totals.put("retained_video_bytes",bytes);
        var response=new LinkedHashMap<String,Object>();response.put("schema_version","recording-review-v1");response.put("items",page);response.put("has_more",more);response.put("next_cursor",more?page.getLast().get("frame_id"):null);response.put("totals",totals);response.put("inference_invoked",false);return response;
    }
    private Map<String,Object> row(UUID frame){
        var filter=filter(null);var args=new ArrayList<>(filter.values());args.add(frame);
        var rows=db.queryForList(COLUMNS+JOINS+filter.sql()+" AND c.frame_id=?",args.toArray());
        if(rows.size()!=1)throw new InputRejected(404,"recording_unavailable");return rows.getFirst();
    }
    public EvidenceFiles.FrameFile image(UUID frame)throws IOException{
        var row=row(frame);recordings.reference(frame,false).orElseThrow(()->new InputRejected(404,"recording_unavailable"));
        var image=images.read(frame,(String)row.get("evidence_ref"));
        if(image==null||!image.sha256().equals(row.get("payload_sha256")))throw new InputRejected(409,"recording_pixels_changed");return image;
    }
    public FrameRecordings.Playback playback(UUID frame){row(frame);return recordings.playback(frame,false).orElseThrow(()->new InputRejected(404,"recording_unavailable"));}
    public void audit(UUID frame,String actor,String kind){
        if(!Set.of("detail","image","video").contains(kind))throw new IllegalArgumentException("invalid recording audit kind");
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,"recording_review."+kind,"verified_source",frame);
    }
    public Map<String,Object> detail(UUID frame)throws IOException{
        var view=new LinkedHashMap<>(view(row(frame)));var image=image(frame);var asset=playback(frame);
        view.put("image",Map.of("width",image.width(),"height",image.height(),"sha256",image.sha256(),"bytes",image.bytes().length));
        view.put("video",video(asset.segmentId(),asset.seekOffsetMs(),asset.manifestSha256(),((Number)asset.manifest().get("duration_us")).longValue(),asset.bytes()));
        return view;
    }
    static Map<String,Object> view(Map<String,Object> row){
        var value=new LinkedHashMap<String,Object>();value.put("frame_id",row.get("frame_id").toString());value.put("camera_id",row.get("camera_id"));value.put("camera_name",row.get("display_name"));value.put("source_type",row.get("source_type"));
        value.put("registered_at",((Timestamp)row.get("registered_at")).toInstant().toString());value.put("target_count",((Number)row.get("target_count")).intValue());value.put("analysis_handoff",row.get("enqueued_at")!=null?"submitted":Boolean.TRUE.equals(row.get("admission_requested"))?"pending":"held");value.put("payload_sha256",row.get("payload_sha256"));
        value.put("video",video((UUID)row.get("segment_id"),((Number)row.get("seek_offset_ms")).longValue(),(String)row.get("manifest_sha256"),((Number)row.get("duration_us")).longValue(),((Number)row.get("video_bytes")).longValue()));return value;
    }
    private static Map<String,Object> video(UUID segment,long offset,String sha,long duration,long bytes){return Map.of("available",true,"kind","session_segment","recording_id",segment.toString(),"seek_offset_ms",offset,"recording_manifest_sha256",sha,"duration_ms",duration/1000,"bytes",bytes);}
}
