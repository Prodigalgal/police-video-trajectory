package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.UserService;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SpecialistOperations {
    public record Control(Boolean enabled,long expected_revision){}
    private final JdbcTemplate db;private final UserService users;private final SearchOrchestrator search;
    public SpecialistOperations(JdbcTemplate db,UserService users,SearchOrchestrator search){this.db=db;this.users=users;this.search=search;}
    public Map<String,Object> status(){
        var row=db.queryForMap("SELECT enabled,revision FROM processing_controls WHERE control_key='specialist_claims'");
        var result=new LinkedHashMap<String,Object>(row);result.put("main_analysis_enabled",Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class)));
        var cameras=search.readableCameras().stream().sorted().toList();
        if(cameras.isEmpty()){result.put("counts",List.of());result.put("recent",List.of());return result;}
        String scope=String.join(",",Collections.nCopies(cameras.size(),"?"));
        result.put("counts",db.queryForList("SELECT state,count(*) AS count FROM specialist_analysis_jobs WHERE camera_id IN ("+scope+") GROUP BY state",cameras.toArray()));
        var recent=new ArrayList<Map<String,Object>>();
        for(var job:db.queryForList("SELECT j.*,j.lease_expires_at>clock_timestamp() AS alive,j.next_attempt_at>clock_timestamp() AS retry_wait,i.anchor_frame_id,i.manifest_sha256 FROM specialist_analysis_jobs j JOIN analysis_inputs i ON i.input_id=j.input_id JOIN observations o ON o.observation_id=i.anchor_observation_id WHERE j.camera_id IN ("+scope+") AND NOT o.deleted AND o.record_version=i.anchor_record_version ORDER BY j.created_at DESC,j.job_id DESC LIMIT 20",cameras.toArray())){
            var input=Map.of("input_id",job.get("input_id").toString(),"anchor_frame_id",job.get("anchor_frame_id").toString(),"camera_id",job.get("camera_id"),"manifest_sha256",job.get("manifest_sha256"));
            recent.add(Map.of("input",input,"job",SpecialistJobs.summary(job,Boolean.TRUE.equals(row.get("enabled")))));
        }
        result.put("recent",recent);return result;
    }
    @Transactional
    public Map<String,Object> control(Control body,String actor){
        if(body==null||body.enabled()==null||body.expected_revision()<1)throw new IllegalArgumentException();users.requireAdmin(actor);
        db.execute("SELECT pg_advisory_xact_lock(918273648)");
        if(db.update("UPDATE processing_controls SET enabled=?,revision=revision+1,updated_at=clock_timestamp() WHERE control_key='specialist_claims' AND revision=?",body.enabled(),body.expected_revision())!=1)throw new InputRejected(409,"specialist_control_conflict");
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'specialist.control',?,?)",actor,body.enabled()?"消费已恢复":"消费已暂停",UUID.randomUUID());return status();
    }
}
