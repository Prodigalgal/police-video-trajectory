package cn.trial.videosearch.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.util.*;

/** Read-only discovery; result access still revalidates the frozen input and pixels. */
@Service
public class ObservationSpecialists {
    private static final String FROM="""
        FROM specialist_analysis_jobs j JOIN analysis_inputs i ON i.input_id=j.input_id
        JOIN observations o ON o.observation_id=i.anchor_observation_id
        WHERE i.anchor_observation_id=? AND i.anchor_frame_id=? AND i.camera_id=?
          AND i.purpose='single_frame' AND NOT o.deleted
        """;
    private static final String COLUMNS="""
        j.job_id,j.input_id,j.input_manifest_sha256,j.frame_id,j.camera_id,j.operation,j.profile,j.input_sha256,
        j.state,j.attempts,j.revision,j.last_error_code,j.result_sha256,j.created_by,j.created_at,j.updated_at,j.next_attempt_at,
        j.lease_expires_at>clock_timestamp() AS alive,j.next_attempt_at>clock_timestamp() AS retry_wait,
        i.anchor_observation_id,i.anchor_record_version,i.anchor_frame_id,i.purpose,
        i.created_by AS input_created_by,i.created_at AS input_created_at,i.manifest_sha256 AS saved_manifest_sha256,
        o.record_version AS current_version
        """;
    private final JdbcTemplate db;
    private final TemporalEvidence evidence;
    private final boolean enabled;

    public ObservationSpecialists(JdbcTemplate db,TemporalEvidence evidence,@Value("${VIDEO_SPECIALIST_JOBS_ENABLED:false}")boolean enabled){
        this.db=db;this.evidence=evidence;this.enabled=enabled;
    }

    public Map<String,Object> list(UUID observation,long revision,int limit,UUID after){
        if(!enabled)throw new InputRejected(404,"specialist_disabled");
        if(observation==null||revision<1||revision>9007199254740991L||limit<1||limit>20)throw new IllegalArgumentException("Invalid observation specialist page");
        var anchor=evidence.anchor(observation,revision);
        var parameters=new ArrayList<Object>(List.of(observation,anchor.frame().id(),anchor.frame().camera()));
        String bounds="";
        if(after!=null){
            var cursor=db.queryForList("SELECT j.created_at "+FROM+" AND j.job_id=?",observation,anchor.frame().id(),anchor.frame().camera(),after);
            if(cursor.size()!=1)throw new InputRejected(400,"invalid_specialist_cursor");
            bounds=" AND (j.created_at,j.job_id)<(?,?)";
            parameters.add(cursor.getFirst().get("created_at"));parameters.add(after);
        }
        parameters.add(limit+1);
        var rows=db.queryForList("SELECT "+COLUMNS+FROM+bounds+" ORDER BY j.created_at DESC,j.job_id DESC LIMIT ?",parameters.toArray());
        boolean more=rows.size()>limit;
        boolean claims=Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class));
        var page=rows.subList(0,Math.min(limit,rows.size()));
        var items=page.stream().map(row->entry(row,anchor,claims)).toList();
        // Recheck the source revision even for an empty page; no long read transaction or image I/O.
        evidence.anchor(observation,revision);
        var scope=new LinkedHashMap<String,Object>();scope.put("observation_id",observation.toString());scope.put("record_version",revision);
        scope.put("frame_id",anchor.frame().id().toString());scope.put("camera_id",anchor.frame().camera());
        var response=new LinkedHashMap<String,Object>();response.put("schema_version","observation-specialists-v1");response.put("anchor",scope);
        response.put("items",items);response.put("has_more",more);response.put("next_cursor",more?page.getLast().get("job_id").toString():null);
        return response;
    }

    private static Map<String,Object> entry(Map<String,Object> row,TemporalEvidence.Anchor anchor,boolean claims){
        if(!Objects.equals(row.get("anchor_observation_id"),anchor.id())||!Objects.equals(row.get("frame_id"),anchor.frame().id())
            ||!Objects.equals(row.get("camera_id"),anchor.frame().camera())||!Objects.equals(row.get("anchor_frame_id"),row.get("frame_id"))
            ||!Objects.equals(row.get("input_manifest_sha256"),row.get("saved_manifest_sha256"))
            ||!(row.get("current_version") instanceof Number current)||current.longValue()!=anchor.version())
            throw new InputRejected(409,"specialist_input_changed");
        var input=new LinkedHashMap<String,Object>();
        for(String key:List.of("input_id","camera_id","anchor_observation_id","anchor_record_version","anchor_frame_id","purpose","current_version"))input.put(key,row.get(key));
        input.put("created_by",row.get("input_created_by"));input.put("created_at",row.get("input_created_at"));input.put("manifest_sha256",row.get("saved_manifest_sha256"));
        if(!(row.get("created_at") instanceof Timestamp)||!(input.get("created_at") instanceof Timestamp))throw new InputRejected(409,"specialist_input_changed");
        return Map.of("input",AnalysisInputs.summary(input),"job",SpecialistJobs.summary(row,claims));
    }
}
