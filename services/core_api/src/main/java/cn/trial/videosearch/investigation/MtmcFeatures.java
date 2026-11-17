package cn.trial.videosearch.investigation;

import cn.trial.videosearch.evidence.AppearanceCatalog;
import cn.trial.videosearch.evidence.AppearanceVectors;
import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Reads only the exact immutable profile and currently bound source descriptor. */
@Repository
public class MtmcFeatures {
    public record Feature(List<Double> vector,String sourceSha256,String vectorSha256,String contentSha256){}
    record Representation(List<Double> vector,String mode){}
    private final JdbcTemplate db;
    public MtmcFeatures(JdbcTemplate db){this.db=db;}
    public Optional<Feature> current(AppearanceCatalog.Profile profile,Map<String,Object> source){
        String sourceSha=CanonicalJson.encode(source,16384).sha256();
        var rows=db.queryForList("""
            SELECT v.vector,v.vector_sha256 FROM appearance_jobs j JOIN appearance_features v ON v.job_id=j.job_id
            WHERE j.profile_sha256=? AND j.source_sha256=? AND j.observation_id=? AND j.record_version=?
             AND j.frame_id=? AND j.camera_id=? AND j.target_type=? AND j.state='completed' LIMIT 2
            """,profile.sha256(),sourceSha,UUID.fromString((String)source.get("observation_id")),source.get("record_version"),UUID.fromString((String)source.get("frame_id")),source.get("camera_id"),source.get("target_type"));
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1)throw new InputRejected(503,"mtmc_feature_unavailable");
        var row=rows.getFirst();var sqlArray=(java.sql.Array)row.get("vector");var vector=new ArrayList<Double>();
        try{for(Object value:(Object[])sqlArray.getArray())vector.add(((Number)value).doubleValue());}
        catch(java.sql.SQLException|ClassCastException failure){throw new InputRejected(503,"mtmc_feature_unavailable");}
        finally{try{sqlArray.free();}catch(java.sql.SQLException failure){throw new InputRejected(503,"mtmc_feature_unavailable");}}
        try{AppearanceVectors.validate(vector,profile.dimensions());}catch(IllegalArgumentException invalid){throw new InputRejected(503,"mtmc_feature_unavailable");}
        return Optional.of(new Feature(List.copyOf(vector),sourceSha,(String)row.get("vector_sha256"),CanonicalJson.encode(vector,65536).sha256()));
    }
    static List<Double> centroid(List<Feature> features,int dimensions){
        return representation(features,dimensions).vector();
    }
    static Representation representation(List<Feature> features,int dimensions){
        if(features.isEmpty()||features.size()>5)throw new IllegalArgumentException();double[] mean=new double[dimensions];
        for(var feature:features){AppearanceVectors.validate(feature.vector(),dimensions);for(int index=0;index<dimensions;index++)mean[index]+=feature.vector().get(index);}
        double norm=0;for(double value:mean)norm+=value*value;norm=Math.sqrt(norm);
        // Opposing / incoherent local features must not create a zero query vector.
        if(norm<1e-8)return new Representation(features.getFirst().vector(),"anchor_only_incoherent_local_features");
        var vector=new ArrayList<Double>();for(double value:mean)vector.add(value/norm);return new Representation(List.copyOf(vector),features.size()>1?"local_track_centroid":"single_frame");
    }
    public Map<String,Object> clock(UUID frame){
        var rows=db.queryForList("SELECT task_id,media_offset_ms,scene_run_id,media_epoch,relative_media_ms,source_time_origin,CASE WHEN source_time_origin='verified' THEN source_time END AS source_time FROM capture_outbox WHERE frame_id=?",frame);
        if(rows.size()!=1)return Map.of();var output=new LinkedHashMap<String,Object>();
        rows.getFirst().forEach((key,value)->output.put(key,value instanceof UUID?value.toString():value instanceof java.sql.Timestamp timestamp?timestamp.toInstant().toString():value));return output;
    }
}
