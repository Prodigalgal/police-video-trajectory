package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.InputRejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Reclaims only obsolete attempts; a live artifact or an in-flight transfer keeps its files. */
@Service
public class ExportArtifactLifecycle {
    public record Claim(String workerId){}
    public record Lease(String workerId,UUID token){}
    private static final String ELIGIBLE="""
        a.reclaimed_at IS NULL
        AND NOT (e.state='active' AND e.lease_generation=a.generation AND e.lease_expires_at>clock_timestamp())
        AND NOT (e.state='completed' AND e.artifact_generation=a.generation)
        AND NOT EXISTS(SELECT 1 FROM evidence_export_transfers t WHERE t.export_id=e.export_id
          AND t.state='streaming' AND t.started_at>clock_timestamp()-interval '20 minutes')
        """;
    private final JdbcTemplate db;private final TransactionTemplate tx;private final boolean enabled;
    public ExportArtifactLifecycle(JdbcTemplate db,PlatformTransactionManager manager,@Value("${VIDEO_EXPORTS_ENABLED:false}")boolean enabled){this.db=db;this.enabled=enabled;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    private void requireEnabled(){if(!enabled)throw new InputRejected(404,"exports_disabled");}
    private void lock(){db.execute("SELECT pg_advisory_xact_lock(918273695)");}
    private static void worker(String value){if(value==null||!value.matches("[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Invalid reclaim owner");}
    public void register(UUID id,long generation,String worker,String sha){db.update("INSERT INTO evidence_export_attempts(export_id,generation,worker_id,manifest_sha256) VALUES(?,?,?,?)",id,generation,worker,sha);}
    public Map<String,Object> claim(Claim body){
        requireEnabled();if(body==null)throw new IllegalArgumentException();worker(body.workerId());
        return tx.execute(status->{lock();var rows=db.queryForList("SELECT a.export_id,a.generation,a.worker_id,a.manifest_sha256 FROM evidence_export_attempts a JOIN evidence_exports e USING(export_id) WHERE "+ELIGIBLE+" AND (a.reclaim_expires_at IS NULL OR a.reclaim_expires_at<=clock_timestamp()) ORDER BY a.created_at,a.export_id,a.generation FOR UPDATE OF a SKIP LOCKED LIMIT 1");
            if(rows.isEmpty())return Map.of("reclaim_available",false);var value=rows.getFirst();UUID token=UUID.randomUUID();
            db.update("UPDATE evidence_export_attempts SET reclaim_token=?,reclaim_owner=?,reclaim_expires_at=clock_timestamp()+interval '60 seconds' WHERE export_id=? AND generation=?",token,body.workerId(),value.get("export_id"),value.get("generation"));
            return Map.of("reclaim",Map.of("export_id",value.get("export_id").toString(),"generation",value.get("generation"),"worker_id",value.get("worker_id"),"manifest_sha256",value.get("manifest_sha256"),"token",token.toString()));});
    }
    private void valid(UUID id,long generation,Lease body){
        if(body==null||body.token()==null||generation<1)throw new IllegalArgumentException();worker(body.workerId());
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM evidence_export_attempts a JOIN evidence_exports e USING(export_id) WHERE a.export_id=? AND a.generation=? AND a.reclaim_token=? AND a.reclaim_owner=? AND a.reclaim_expires_at>clock_timestamp() AND "+ELIGIBLE+")",Boolean.class,id,generation,body.token(),body.workerId())))throw new InputRejected(409,"export_reclaim_expired");
    }
    public Map<String,Object> verify(UUID id,long generation,Lease body){requireEnabled();return tx.execute(status->{lock();valid(id,generation,body);return Map.of("reclaim_allowed",true);});}
    public Map<String,Object> complete(UUID id,long generation,Lease body){
        requireEnabled();if(body==null||body.token()==null)throw new IllegalArgumentException();worker(body.workerId());
        return tx.execute(status->{lock();if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM evidence_export_attempts WHERE export_id=? AND generation=? AND reclaim_token=? AND reclaim_owner=? AND reclaimed_at IS NOT NULL)",Boolean.class,id,generation,body.token(),body.workerId())))return Map.of("reclaimed",true);
            valid(id,generation,body);db.update("UPDATE evidence_export_attempts SET reclaimed_at=clock_timestamp(),reclaim_expires_at=NULL WHERE export_id=? AND generation=?",id,generation);
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'exports.reclaim',?,?)",body.workerId(),"generation="+generation,id);return Map.of("reclaimed",true);});
    }
}
