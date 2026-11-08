package cn.trial.videosearch.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public record IngestionPolicy(boolean enabled,int totalLimit,int rtspLimit,int archiveLimit,int maxFailures){
    public IngestionPolicy(@Value("${VIDEO_INGEST_JOBS_ENABLED:false}")boolean enabled,@Value("${VIDEO_INGEST_TOTAL_LIMIT:5}")int totalLimit,
        @Value("${VIDEO_INGEST_RTSP_LIMIT:4}")int rtspLimit,@Value("${VIDEO_INGEST_ARCHIVE_LIMIT:1}")int archiveLimit,
        @Value("${VIDEO_INGEST_MAX_FAILURES:5}")int maxFailures){
        if(totalLimit<1||totalLimit>16||rtspLimit<0||rtspLimit>totalLimit||archiveLimit<0||archiveLimit>totalLimit||maxFailures<1||maxFailures>10)throw new IllegalArgumentException("Invalid ingestion policy");
        this.enabled=enabled;this.totalLimit=totalLimit;this.rtspLimit=rtspLimit;this.archiveLimit=archiveLimit;this.maxFailures=maxFailures;
    }
    public void lock(JdbcTemplate db){db.execute("SELECT pg_advisory_xact_lock(918273694)");}
    public boolean managedSchemaPresent(JdbcTemplate db){return Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('ingestion_task_specs') IS NOT NULL",Boolean.class));}
    public String liveSourceGuard(String alias){
        if(!Set.of("t","processing_tasks").contains(alias))throw new IllegalArgumentException("Unknown source task alias");
        return "AND ("+alias+".task_type<>'rtsp' OR NOT EXISTS(SELECT 1 FROM processing_tasks running WHERE running.source_id="+alias+".source_id AND running.task_id<>"+alias+".task_id AND running.state='active' AND running.lease_expires_at>clock_timestamp()))";
    }
    public boolean available(JdbcTemplate db,String type){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class)))return false;
        var load=db.queryForList("SELECT count(*) AS total,count(*) FILTER(WHERE task_type=?) AS same_type FROM processing_tasks WHERE state='active' AND lease_expires_at>clock_timestamp()",type).getFirst();
        return ((Number)load.get("total")).intValue()<totalLimit&&((Number)load.get("same_type")).intValue()<("rtsp".equals(type)?rtspLimit:archiveLimit);
    }
    public int retrySeconds(int failures){return Math.min(300,5*(1<<Math.min(6,Math.max(0,failures-1))));}
    public Map<String,Object> view(){return Map.of("total_limit",totalLimit,"rtsp_limit",rtspLimit,"archive_limit",archiveLimit,"max_failures",maxFailures);}
}
