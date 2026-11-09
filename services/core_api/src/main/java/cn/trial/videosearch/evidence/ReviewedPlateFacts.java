package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.PlateNumbers;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Current confirmation and original-source bindings, independent of any search snapshot. */
@Service
public class ReviewedPlateFacts {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_CONFIRMATIONS=32;
    private final JdbcTemplate db;private final boolean enabled;
    public ReviewedPlateFacts(JdbcTemplate db,@Value("${VIDEO_REVIEWED_PLATE_SEARCH_ENABLED:false}")boolean enabled){this.db=db;this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    public record Snapshot(UUID observation,long version,UUID frame,String camera,String evidence,String outcome,String number,List<Map<String,Object>> proofs,String sha256){}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value){return (Map<String,Object>)JSON.readValue(value.toString(),Map.class);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Snapshot resolve(UUID observation){
        var rows=db.queryForList("SELECT o.record_version,o.frame_id,o.camera_id,o.target_type,o.deleted,o.bbox_xyxy::text AS box,f.analysis_state,c.payload_sha256 FROM observations o JOIN frames f ON f.frame_id=o.frame_id LEFT JOIN capture_outbox c ON c.frame_id=o.frame_id WHERE o.observation_id=?",observation);
        if(rows.size()!=1)return snapshot(observation,0,null,"",null,"source_changed",null,List.of());
        var source=rows.getFirst();long version=((Number)source.get("record_version")).longValue();UUID frame=(UUID)source.get("frame_id");String camera=(String)source.get("camera_id"),sha=(String)source.get("payload_sha256");
        if(Boolean.TRUE.equals(source.get("deleted"))||!"vehicle".equals(source.get("target_type"))||!"completed".equals(source.get("analysis_state"))||sha==null||source.get("box")==null)return snapshot(observation,version,frame,camera,sha,"source_changed",null,List.of());
        var confirmations=db.queryForList("""
            SELECT r.job_id,r.revision AS review_revision,r.region_index,r.result_sha256,r.review_scope,
             j.input_id,j.frame_id,j.camera_id,j.profile,j.input_sha256,j.input_manifest_sha256,j.state,j.input_descriptor::text AS descriptor,
             i.anchor_record_version,i.anchor_frame_id,i.camera_id AS input_camera,i.manifest_sha256,i.manifest::text AS manifest,
             s.result::text AS result,s.result_sha256 AS stored_sha,j.result_sha256 AS job_sha
            FROM specialist_plate_reviews r JOIN specialist_analysis_jobs j ON j.job_id=r.job_id
             JOIN analysis_inputs i ON i.input_id=j.input_id JOIN specialist_analysis_results s ON s.job_id=j.job_id
            WHERE i.anchor_observation_id=? AND r.review_state='confirmed' AND j.operation='license_plate'
            ORDER BY r.job_id LIMIT ?
            """,observation,MAX_CONFIRMATIONS+1);
        if(confirmations.size()>MAX_CONFIRMATIONS)return snapshot(observation,version,frame,camera,sha,"conflict",null,List.of());
        var proofs=new ArrayList<Map<String,Object>>();var numbers=new TreeSet<String>();
        for(var confirmation:confirmations){
            try{
                if(((Number)confirmation.get("anchor_record_version")).longValue()!=version||!frame.equals(confirmation.get("anchor_frame_id"))||!frame.equals(confirmation.get("frame_id"))||!camera.equals(confirmation.get("camera_id"))||!camera.equals(confirmation.get("input_camera"))||!"completed".equals(confirmation.get("state")))continue;
                var result=object(confirmation.get("result"));var manifest=object(confirmation.get("manifest"));
                String inputSha=(String)confirmation.get("manifest_sha256");
                if(!CanonicalJson.encode(manifest,512*1024).sha256().equals(inputSha)||!inputSha.equals(confirmation.get("input_manifest_sha256")))throw new IllegalArgumentException();
                var descriptor=SpecialistResult.input((UUID)confirmation.get("input_id"),new AnalysisInputs.Verified(manifest,"",inputSha,null),"license_plate",(String)confirmation.get("profile"));
                String descriptorSha=CanonicalJson.encode(descriptor,16384).sha256();
                if(!descriptorSha.equals(confirmation.get("input_sha256"))||!descriptorSha.equals(CanonicalJson.encode(object(confirmation.get("descriptor")),16384).sha256())||!sha.equals(descriptor.get("evidence_sha256")))throw new IllegalArgumentException();
                var encoded=SpecialistResult.validate((UUID)confirmation.get("job_id"),descriptorSha,descriptor,result);
                if(!encoded.sha256().equals(confirmation.get("result_sha256"))||!encoded.sha256().equals(confirmation.get("stored_sha"))||!encoded.sha256().equals(confirmation.get("job_sha")))throw new IllegalArgumentException();
                var anchor=SpecialistResult.object(manifest.get("anchor"));
                if(!observation.toString().equals(anchor.get("observation_id"))||((Number)anchor.get("record_version")).longValue()!=version)throw new IllegalArgumentException();
                Object box=JSON.readValue(source.get("box").toString(),Object.class);
                var binding=Map.<String,Object>of("selection_mode","requested","frame_id",frame.toString(),"jpeg_sha256",sha,"vehicle_bbox_xyxy",box);
                var target=PlateReviewTarget.requested(binding,Map.of("frame_id",frame),result);
                int region=((Number)confirmation.get("region_index")).intValue();
                if(target.isEmpty()||!target.get().eligible().contains(region))throw new IllegalArgumentException();
                var reading=SpecialistResult.object(((List<?>)result.get("regions")).get(region));String number=PlateNumbers.normalize((String)reading.get("text"));numbers.add(number);
                proofs.add(Map.of("job_id",confirmation.get("job_id").toString(),"review_revision",confirmation.get("review_revision"),"result_sha256",encoded.sha256(),"region_index",region,"input_manifest_sha256",inputSha,"review_scope",confirmation.get("review_scope")));
            }catch(RuntimeException invalid){return snapshot(observation,version,frame,camera,sha,"invalid_source",null,List.of());}
        }
        String outcome=numbers.isEmpty()?"empty":numbers.size()>1?"conflict":"ready";
        return snapshot(observation,version,frame,camera,sha,outcome,numbers.size()==1?numbers.first():null,List.copyOf(proofs));
    }
    private static Snapshot snapshot(UUID observation,long version,UUID frame,String camera,String evidence,String outcome,String number,List<Map<String,Object>> proofs){
        var binding=new LinkedHashMap<String,Object>();binding.put("observation_id",observation.toString());binding.put("record_version",version);binding.put("frame_id",frame==null?null:frame.toString());binding.put("camera_id",camera);binding.put("evidence_sha256",evidence);binding.put("outcome",outcome);binding.put("number",number);binding.put("proofs",proofs);
        return new Snapshot(observation,version,frame,camera,evidence,outcome,number,proofs,CanonicalJson.encode(binding,65536).sha256());
    }
    public void validateHits(List<Map<String,Object>> hits,String number){
        if(hits.isEmpty())return;
        var ids=hits.stream().map(hit->UUID.fromString((String)hit.get("observation_id"))).toList();
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var current=new HashMap<UUID,Map<String,Object>>();
        for(var row:db.queryForList("SELECT p.*,o.record_version,o.frame_id,o.camera_id,o.target_type,o.deleted,c.payload_sha256,f.analysis_state FROM reviewed_plate_index_tasks p JOIN observations o ON o.observation_id=p.observation_id JOIN frames f ON f.frame_id=o.frame_id LEFT JOIN capture_outbox c ON c.frame_id=o.frame_id WHERE p.observation_id IN ("+placeholders+")",ids.toArray()))current.put((UUID)row.get("observation_id"),row);
        for(var hit:hits){
            var row=current.get(UUID.fromString((String)hit.get("observation_id")));
            if(row==null||Boolean.TRUE.equals(row.get("deleted"))||!"vehicle".equals(row.get("target_type"))||!"completed".equals(row.get("analysis_state"))||!"ready".equals(row.get("projection_outcome"))||!Objects.equals(row.get("requested_revision"),row.get("published_revision"))||!sameNumber(row.get("requested_revision"),hit.get("plate_projection_revision"))||!sameNumber(row.get("record_version"),hit.get("record_version"))||!sameNumber(row.get("source_record_version"),hit.get("record_version"))||!Objects.equals(row.get("evidence_sha256"),row.get("payload_sha256"))||!Objects.equals(row.get("fact_sha256"),hit.get("plate_fact_sha256"))||!number.equals(hit.get("reviewed_plate_number"))||!row.get("frame_id").toString().equals(hit.get("frame_id"))||!row.get("camera_id").equals(hit.get("camera_id")))throw new cn.trial.videosearch.search.SearchCursorCodec.Invalid("plate_search_changed");
        }
    }
    private static boolean sameNumber(Object first,Object second){return (first instanceof Integer||first instanceof Long)&&(second instanceof Integer||second instanceof Long)&&((Number)first).longValue()==((Number)second).longValue();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Map<String,Object> details(UUID observation,long version){
        if(!enabled)return Map.of("state","disabled");
        var snapshot=resolve(observation);if(snapshot.version()!=version)return Map.of("state","source_changed");
        var value=new LinkedHashMap<String,Object>();value.put("state",snapshot.outcome());value.put("number",snapshot.number());value.put("proofs",snapshot.proofs());value.put("source_scope","current_frame_only");return value;
    }
}
