package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.util.*;

/** Bounded queries in the capture clock, never in queue arrival order. */
@Service
public class TemporalEvidence {
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private static final String COLUMNS="""
        f.frame_id,f.task_id,f.evidence_ref,f.media_offset_ms,f.source_time,f.source_time_origin,f.received_at,
        s.source_id,s.camera_id,s.source_type,s.source_ref,c.scene_run_id,c.media_epoch,c.relative_media_ms,
        c.payload_sha256,c.plan->>'image_width' AS image_width,c.plan->>'image_height' AS image_height
        """;
    private static final String FROM="""
        FROM frames f JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id
        LEFT JOIN capture_outbox c ON c.frame_id=f.frame_id
        """;
    private static final String VISIBLE="""
        f.analysis_state='completed' AND EXISTS(SELECT 1 FROM observations v
        WHERE v.frame_id=f.frame_id AND v.camera_id=s.camera_id AND NOT v.deleted)
        AND (c.frame_id IS NULL OR (c.task_id=f.task_id AND c.camera_id=s.camera_id AND c.source_type=s.source_type
        AND c.evidence_ref=f.evidence_ref AND c.source_time_origin=f.source_time_origin
        AND c.source_time IS NOT DISTINCT FROM f.source_time AND c.media_offset_ms IS NOT DISTINCT FROM f.media_offset_ms))
        """;
    public TemporalEvidence(JdbcTemplate db,SearchOrchestrator search){this.db=db;this.search=search;}
    public record Frame(Map<String,Object> values){
        public UUID id(){return (UUID)values.get("frame_id");}
        public String camera(){return (String)values.get("camera_id");}
        public Long millis(){return values.get("relative_media_ms") instanceof Number n?n.longValue():null;}
        public String reference(){return (String)values.get("evidence_ref");}
        public String fingerprint(){return CanonicalJson.encode(normalize(values),16384).sha256();}
        public Map<String,Object> view(){
            var output=new LinkedHashMap<String,Object>();
            for(String key:List.of("frame_id","task_id","camera_id","source_type","source_time_origin","received_at","media_offset_ms","scene_run_id","media_epoch","relative_media_ms"))output.put(key,external(values.get(key)));
            output.put("source_time","verified".equals(values.get("source_time_origin"))?external(values.get("source_time")):null);
            output.put("registered_payload_sha256",values.get("payload_sha256"));
            output.put("image_width",dimension(values.get("image_width")));output.put("image_height",dimension(values.get("image_height")));
            output.put("source_fingerprint",fingerprint());return output;
        }
        public boolean clockAvailable(){return values.get("scene_run_id")!=null&&values.get("media_epoch")!=null&&millis()!=null;}
    }
    public record Anchor(UUID id,long version,Map<String,Object> observation,Frame frame){
        public Map<String,Object> view(){
            var output=new LinkedHashMap<String,Object>();output.put("observation_id",id.toString());output.put("record_version",version);
            output.put("frame_id",frame.id().toString());
            for(String key:List.of("target_type","model_id","prompt_version"))output.put(key,observation.get(key));
            return output;
        }
        public String fingerprint(){return CanonicalJson.encode(normalize(observation),32768).sha256();}
    }
    public record Context(Anchor anchor,TemporalWindow window,List<Frame> frames,TemporalWindow.Segment segment,boolean truncated,Set<UUID> ambiguous){
        public Map<String,Object> view(){
            return Map.of("anchor",anchor.view(),"window",window.value(),"frames",frames.stream().map(frame->{
                var value=frame.view();value.put("ambiguous_time",ambiguous.contains(frame.id()));
                value.put("anchor_segment",segment.frameIds().contains(frame.id()));return value;
            }).toList(),"sequence",Map.of("eligible",segment.eligible(),"reason",segment.reason(),"frame_ids",segment.frameIds().stream().map(UUID::toString).toList()),
                "truncated",truncated,"quality",Map.of("continuous_video_verified",false,"identity_association_verified",false));
        }
    }
    public Anchor anchor(UUID id,Long expected){
        if(id==null)throw new IllegalArgumentException("Observation is required");
        var observations=db.queryForList("""
            SELECT o.observation_id,o.frame_id,o.camera_id,o.record_version,o.target_type,o.model_id,o.prompt_version,
            o.bbox_xyxy::text AS observation_bbox,d.target_id,d.object_type AS detector_object_type,d.class_name,
            d.bbox_xyxy::text AS detector_bbox,c.payload_sha256,
            CASE WHEN c.frame_id IS NOT NULL THEN jsonb_build_object('image_width',c.plan->'image_width','image_height',c.plan->'image_height')::text END AS plan_text
            FROM observations o JOIN frames f ON f.frame_id=o.frame_id
            JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id
            LEFT JOIN capture_outbox c ON c.frame_id=f.frame_id
            LEFT JOIN detected_targets d ON d.frame_id=f.frame_id AND d.observation_ordinal=o.object_ordinal
            WHERE o.observation_id=? AND NOT o.deleted AND f.analysis_state='completed' AND o.camera_id=s.camera_id
            """,id);
        if(observations.size()!=1||!search.mayReadCamera((String)observations.getFirst().get("camera_id")))throw new InputRejected(404,"observation_unavailable");
        var row=observations.getFirst();long version=((Number)row.get("record_version")).longValue();
        if(expected!=null&&version!=expected)throw new InputRejected(409,"observation_revision_conflict");
        Frame frame=frame((UUID)row.get("frame_id"));return new Anchor(id,version,row,frame);
    }
    public Frame frame(UUID id){
        return frames(List.of(id)).getFirst();
    }
    public List<Frame> frames(List<UUID> ids){
        if(ids.isEmpty()||ids.size()>24||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Invalid frame selection");
        var rows=db.queryForList("SELECT "+COLUMNS+FROM+" WHERE f.frame_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND "+VISIBLE,ids.toArray());
        if(rows.size()!=ids.size()||rows.stream().anyMatch(row->!search.mayReadCamera((String)row.get("camera_id"))))throw new InputRejected(404,"input_source_unavailable");
        var selected=new HashMap<UUID,Frame>();rows.forEach(row->selected.put((UUID)row.get("frame_id"),new Frame(row)));
        return ids.stream().map(selected::get).toList();
    }
    public Context context(UUID id,Long expected,TemporalWindow window){
        Anchor anchor=anchor(id,expected);Frame center=anchor.frame();
        if(!center.clockAvailable())return new Context(anchor,window,List.of(center),new TemporalWindow.Segment(List.of(center.id()),"clock_unavailable"),false,Set.of());
        long position=center.millis(),start=Math.max(0,position-window.beforeMs());
        long end=position>Long.MAX_VALUE-window.afterMs()?Long.MAX_VALUE:position+window.afterMs();
        var candidates=new ArrayList<Frame>();candidates.add(center);
        String scope=" WHERE "+VISIBLE+" AND s.camera_id=? AND c.task_id=? AND c.scene_run_id=? AND c.media_epoch=? AND c.relative_media_ms BETWEEN ? AND ?";
        for(boolean before:List.of(true,false)){
            String direction=before?"<":">",order=before?"DESC":"ASC";
            var rows=db.queryForList("SELECT "+COLUMNS+FROM+scope+" AND (c.relative_media_ms,c.frame_id) "+direction+" (?,?) ORDER BY c.relative_media_ms "+order+",c.frame_id "+order+" LIMIT ?",
                center.camera(),center.values().get("task_id"),center.values().get("scene_run_id"),center.values().get("media_epoch"),start,end,position,center.id(),window.maxFrames()+1);
            rows.stream().map(Frame::new).forEach(candidates::add);
        }
        candidates.sort(Comparator.comparingLong((Frame f)->f.id().equals(center.id())?-1:Math.abs(f.millis()-position)).thenComparing(f->f.id().toString()));
        boolean truncated=candidates.size()>window.maxFrames();
        List<Frame> selected=new ArrayList<>(candidates.subList(0,Math.min(candidates.size(),window.maxFrames())));
        selected.sort(Comparator.comparingLong(Frame::millis).thenComparing(f->f.id().toString()));
        var ambiguous=new HashSet<UUID>();
        // One bounded batch, with indexed EXISTS, also detects duplicates outside the page.
        var parameters=new ArrayList<Object>();
        for(Frame frame:selected){parameters.add(frame.id());parameters.add(frame.millis());}
        parameters.addAll(List.of(center.camera(),center.values().get("task_id"),center.values().get("scene_run_id"),center.values().get("media_epoch"),start,end));
        String positions=String.join(",",Collections.nCopies(selected.size(),"(?::uuid,?::bigint)"));
        for(var duplicate:db.queryForList("SELECT wanted.frame_id FROM (VALUES "+positions+") wanted(frame_id,millis) WHERE EXISTS(SELECT 1 "+FROM+scope+" AND c.relative_media_ms=wanted.millis AND f.frame_id<>wanted.frame_id LIMIT 1)",parameters.toArray()))ambiguous.add((UUID)duplicate.get("frame_id"));
        var segment=window.segment(selected.stream().map(f->new TemporalWindow.Position(f.id(),f.millis(),ambiguous.contains(f.id()))).toList(),center.id());
        return new Context(anchor,window,List.copyOf(selected),segment,truncated,Set.copyOf(ambiguous));
    }
    public void unchanged(Anchor anchor,List<Frame> frames){
        Anchor current=anchor(anchor.id(),anchor.version());
        if(!current.fingerprint().equals(anchor.fingerprint()))throw new InputRejected(409,"input_source_changed");
        var currentFrames=frames(frames.stream().map(Frame::id).toList());
        for(int i=0;i<frames.size();i++)if(!currentFrames.get(i).fingerprint().equals(frames.get(i).fingerprint()))throw new InputRejected(409,"input_source_changed");
    }
    /** Short shared locks close the final metadata-check/write race; no file reads here. */
    public void lockSources(Anchor anchor,List<Frame> frames){
        for(Frame frame:frames.stream().sorted(Comparator.comparing(f->f.id().toString())).toList()){
            db.queryForList("SELECT frame_id FROM frames WHERE frame_id=? FOR SHARE",frame.id());
            db.queryForList("SELECT frame_id FROM capture_outbox WHERE frame_id=? FOR SHARE",frame.id());
            db.queryForList("SELECT observation_id FROM observations WHERE frame_id=? ORDER BY observation_id FOR SHARE",frame.id());
            db.queryForList("SELECT target_id FROM detected_targets WHERE frame_id=? ORDER BY target_id FOR SHARE",frame.id());
        }
        for(Object task:frames.stream().map(f->f.values().get("task_id")).distinct().toList())db.queryForList("SELECT task_id FROM processing_tasks WHERE task_id=? FOR SHARE",task);
        for(Object source:frames.stream().map(f->f.values().get("source_id")).distinct().toList())db.queryForList("SELECT source_id FROM sources WHERE source_id=? FOR SHARE",source);
    }
    static Integer dimension(Object raw){
        try{int value=Integer.parseInt(String.valueOf(raw));return value>0&&value<=20000?value:null;}
        catch(NumberFormatException error){return null;}
    }
    private static Object external(Object value){return value instanceof UUID id?id.toString():value instanceof Timestamp time?time.toInstant().toString():value;}
    private static Map<String,Object> normalize(Map<String,Object> values){
        var normalized=new LinkedHashMap<String,Object>();values.forEach((key,value)->normalized.put(key,external(value)));return normalized;
    }
}
