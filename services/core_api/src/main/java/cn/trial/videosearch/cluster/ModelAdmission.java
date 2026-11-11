package cn.trial.videosearch.cluster;

import cn.trial.videosearch.evidence.InputRejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ModelAdmission {
    public record Acquire(UUID leaseId,String consumer,Boolean vision,Integer ttlSeconds){}
    public record Release(UUID leaseId,String consumer){}
    private final JdbcTemplate db;private final boolean enabled;private final int total,reserved;
    public ModelAdmission(JdbcTemplate db,@Value("${VIDEO_CLUSTER_COORDINATION_ENABLED:false}")boolean enabled,
            @Value("${VIDEO_CLUSTER_MODEL_TOTAL:2}")int total,@Value("${VIDEO_CLUSTER_MODEL_TEXT_RESERVED:0}")int reserved){
        if(total<1||total>64||reserved<0||reserved>=total)throw new IllegalArgumentException("Invalid cluster model budget");
        this.db=db;this.enabled=enabled;this.total=total;this.reserved=reserved;
    }
    private void lock(){
        if(!enabled)throw new InputRejected(503,"cluster_coordination_disabled");
        db.execute("SELECT pg_advisory_xact_lock(918273699)");db.update("DELETE FROM cluster_model_leases WHERE expires_at<=clock_timestamp()");
        db.update("INSERT INTO cluster_model_policy(policy_key,total,text_reserved) VALUES('global',?,?) ON CONFLICT DO NOTHING",total,reserved);
        var policy=db.queryForList("SELECT total,text_reserved FROM cluster_model_policy WHERE policy_key='global'").getFirst();
        if(((Number)policy.get("total")).intValue()!=total||((Number)policy.get("text_reserved")).intValue()!=reserved)throw new InputRejected(503,"cluster_model_policy_conflict");
    }
    @Transactional(timeout=5)
    public boolean ready(){if(!enabled)return false;lock();return true;}
    private static void identity(UUID id,String consumer){if(id==null||consumer==null||!consumer.matches("[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Invalid model lease");}
    @Transactional(timeout=5)
    public Map<String,Object> acquire(Acquire body){
        if(body==null)throw new IllegalArgumentException();identity(body.leaseId(),body.consumer());
        if(body.vision()==null||body.ttlSeconds()==null||body.ttlSeconds()<5||body.ttlSeconds()>630)throw new IllegalArgumentException("Invalid model deadline");
        lock();var prior=db.queryForList("SELECT consumer,vision FROM cluster_model_leases WHERE lease_id=?",body.leaseId());
        if(!prior.isEmpty()){
            if(!body.consumer().equals(prior.getFirst().get("consumer"))||!body.vision().equals(prior.getFirst().get("vision")))throw new InputRejected(409,"model_lease_conflict");
            return Map.of("granted",true,"lease_id",body.leaseId().toString());
        }
        var counts=db.queryForList("SELECT count(*) AS total,count(*) FILTER(WHERE vision) AS vision FROM cluster_model_leases").getFirst();
        if(((Number)counts.get("total")).longValue()>=total||body.vision()&&((Number)counts.get("vision")).longValue()>=total-reserved)return Map.of("granted",false);
        db.update("INSERT INTO cluster_model_leases(lease_id,consumer,vision,expires_at) VALUES(?,?,?,clock_timestamp()+(?*interval '1 second'))",body.leaseId(),body.consumer(),body.vision(),body.ttlSeconds());
        return Map.of("granted",true,"lease_id",body.leaseId().toString());
    }
    @Transactional(timeout=5)
    public Map<String,Object> release(Release body){
        if(body==null)throw new IllegalArgumentException();identity(body.leaseId(),body.consumer());lock();
        db.update("DELETE FROM cluster_model_leases WHERE lease_id=? AND consumer=?",body.leaseId(),body.consumer());return Map.of("released",true);
    }
}
