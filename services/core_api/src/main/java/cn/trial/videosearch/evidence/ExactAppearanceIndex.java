package cn.trial.videosearch.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;

/** Bounded exact cosine baseline. Reads 256 vectors at a time, keeps only top K. */
@Component
public class ExactAppearanceIndex implements AppearanceIndex {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int BATCH=256,MAX_SCAN=50000;
    private final JdbcTemplate db;
    public ExactAppearanceIndex(JdbcTemplate db){this.db=db;}
    @Override public Found find(AppearanceCatalog.Profile profile,List<Double> vector,Filter filter,int candidates){
        AppearanceVectors.validate(vector,profile.dimensions());if(candidates<1||candidates>1000||filter.cameras().isEmpty())throw new IllegalArgumentException();
        var heap=new PriorityQueue<Ranked>(Comparator.comparingDouble(Ranked::score).thenComparing(hit->(String)hit.source().get("observation_id"),Comparator.reverseOrder()));
        UUID after=null;int scanned=0;long deadline=System.nanoTime()+60_000_000_000L;
        while(true){
            if(System.nanoTime()>deadline)throw new InputRejected(503,"appearance_search_timeout");
            var args=new ArrayList<Object>(List.of(profile.sha256(),filter.type()));args.addAll(filter.cameras());
            String scope=" AND j.camera_id IN ("+String.join(",",Collections.nCopies(filter.cameras().size(),"?"))+")";
            if(filter.start()!=null){scope+=" AND f.source_time_origin='verified' AND f.source_time>=?";args.add(Timestamp.from(filter.start()));}
            if(filter.end()!=null){scope+=" AND f.source_time_origin='verified' AND f.source_time<=?";args.add(Timestamp.from(filter.end()));}
            if(after!=null){scope+=" AND j.job_id>?";args.add(after);}args.add(BATCH);
            var rows=db.queryForList("""
                SELECT j.job_id,j.descriptor::text AS descriptor_text,v.vector,d.track_id,
                  f.task_id,f.media_offset_ms,f.source_time_origin,f.source_time,f.received_at,
                  c.scene_run_id,c.media_epoch,c.relative_media_ms
                FROM appearance_jobs j JOIN appearance_features v ON v.job_id=j.job_id
                JOIN observations o ON o.observation_id=j.observation_id AND o.record_version=j.record_version AND o.frame_id=j.frame_id AND o.camera_id=j.camera_id AND o.target_type=j.target_type
                JOIN frames f ON f.frame_id=j.frame_id JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id AND s.camera_id=j.camera_id
                JOIN capture_outbox c ON c.frame_id=j.frame_id AND c.task_id=f.task_id AND c.camera_id=j.camera_id
                JOIN detected_targets d ON d.frame_id=j.frame_id AND d.observation_ordinal=o.object_ordinal AND d.object_type=o.target_type
                WHERE j.profile_sha256=? AND j.target_type=? AND j.state='completed' AND NOT o.deleted AND f.analysis_state='completed'
                  AND c.payload_sha256=j.descriptor->>'image_sha256' AND d.target_id::text=j.descriptor->>'target_id'
                  AND d.bbox_xyxy=o.bbox_xyxy AND d.bbox_xyxy=j.descriptor->'bbox_xyxy'
                  AND c.plan->'image_width'=j.descriptor->'image_width' AND c.plan->'image_height'=j.descriptor->'image_height'
                """+scope+" ORDER BY j.job_id LIMIT ?",args.toArray());
            for(var row:rows){
                if(++scanned>MAX_SCAN)throw new InputRejected(422,"appearance_scope_too_large");
                var sqlArray=(java.sql.Array)row.get("vector");float[] candidate;
                try{var values=(Object[])sqlArray.getArray();candidate=new float[values.length];for(int i=0;i<values.length;i++)candidate[i]=((Number)values[i]).floatValue();}
                catch(java.sql.SQLException invalid){throw new InputRejected(503,"appearance_index_unavailable");}
                finally{try{sqlArray.free();}catch(java.sql.SQLException invalid){throw new InputRejected(503,"appearance_index_unavailable");}}
                double score=AppearanceVectors.cosine(vector,candidate);
                var source=AppearanceCatalog.object(JSON.readValue((String)row.get("descriptor_text"),Object.class));
                var clock=new LinkedHashMap<String,Object>();
                for(String key:List.of("task_id","media_offset_ms","source_time_origin","source_time","received_at","scene_run_id","media_epoch","relative_media_ms")){
                    Object value=row.get(key);clock.put(key,value instanceof Timestamp time?time.toInstant().toString():value instanceof UUID?value.toString():value);
                }
                if(!"verified".equals(clock.get("source_time_origin")))clock.put("source_time",null);
                var hit=new Ranked(source,clock,Objects.toString(row.get("track_id"),""),score);
                if(heap.size()<candidates)heap.add(hit);else if(compare(hit,heap.peek())<0){heap.poll();heap.add(hit);}
            }
            if(rows.size()<BATCH)break;after=(UUID)rows.getLast().get("job_id");
        }
        var found=new ArrayList<>(heap);found.sort(ExactAppearanceIndex::compare);return new Found(List.copyOf(found),scanned,scanned>candidates);
    }
    static int compare(Ranked a,Ranked b){int score=Double.compare(b.score(),a.score());return score!=0?score:((String)a.source().get("observation_id")).compareTo((String)b.source().get("observation_id"));}
}
