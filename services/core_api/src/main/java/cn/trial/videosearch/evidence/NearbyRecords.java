package cn.trial.videosearch.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

/** Browse adjacent retained records without inventing a media clock or sequence. */
@Service
public class NearbyRecords {
    private final JdbcTemplate db;
    private final TemporalEvidence temporal;
    private final EvidenceFiles evidence;
    public NearbyRecords(JdbcTemplate db,TemporalEvidence temporal,EvidenceFiles evidence){this.db=db;this.temporal=temporal;this.evidence=evidence;}
    private List<UUID> neighbors(UUID anchor,boolean before,int limit){
        String direction=before?"<":">",order=before?"DESC":"ASC";
        return db.query("""
            SELECT f.frame_id FROM frames f JOIN frames a ON a.frame_id=?
              AND f.task_id=a.task_id AND f.lease_generation=a.lease_generation
            WHERE f.analysis_state='completed' AND (f.received_at,f.frame_id)%s(a.received_at,a.frame_id)
              AND EXISTS(SELECT 1 FROM observations o WHERE o.frame_id=f.frame_id AND NOT o.deleted)
            ORDER BY f.received_at %s,f.frame_id %s LIMIT ?
            """.formatted(direction,order,order),(rs,n)->rs.getObject(1,UUID.class),anchor,limit);
    }
    public Map<String,Object> list(UUID id,Long expected,int limit)throws IOException{
        if(limit<1||limit>17)throw new IllegalArgumentException("Bounded nearby records required");
        var anchor=temporal.anchor(id,expected);UUID center=anchor.frame().id();
        var ids=new ArrayList<>(neighbors(center,true,limit/2));Collections.reverse(ids);ids.add(center);
        ids.addAll(neighbors(center,false,limit-ids.size()));
        var frames=temporal.frames(ids);
        var observations=db.queryForList("SELECT DISTINCT ON(frame_id) frame_id,observation_id FROM observations WHERE NOT deleted AND frame_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY frame_id,object_ordinal,observation_id",ids.toArray());
        var byFrame=new HashMap<UUID,UUID>();observations.forEach(row->byFrame.put((UUID)row.get("frame_id"),(UUID)row.get("observation_id")));byFrame.put(center,id);
        var origin=((Timestamp)anchor.frame().values().get("received_at")).toInstant();
        var items=new ArrayList<Map<String,Object>>();int unavailable=0;
        for(var frame:frames){
            var image=evidence.read(frame.id(),frame.reference());
            Object registered=frame.values().get("payload_sha256");
            if(image==null||registered!=null&&!registered.equals(image.sha256())||!byFrame.containsKey(frame.id())){
                if(frame.id().equals(center))throw new InputRejected(409,"input_evidence_unavailable");
                unavailable++;continue;
            }
            var view=new LinkedHashMap<>(frame.view());view.put("preview_sha256",image.sha256());
            view.put("image_width",image.width());view.put("image_height",image.height());
            view.put("record_offset_ms",Duration.between(origin,((Timestamp)frame.values().get("received_at")).toInstant()).toMillis());
            view.put("observation_id",byFrame.get(frame.id()).toString());items.add(view);
        }
        return Map.of("schema_version","nearby-records-v1","anchor",anchor.view(),"items",items,"time_basis","recorded_at",
            "continuous_video_verified",false,"sequence_eligible",false,"unavailable_frames",unavailable);
    }
}
