package cn.trial.videosearch.index;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.evidence.ReviewedPlateFacts;
import cn.trial.videosearch.http.SecureJsonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Coalescing durable publication. Neither HTTP request holds a database transaction. */
@Service
public class ReviewedPlatePublisher {
    private static final int MAX_ATTEMPTS=10,LEASE_SECONDS=90;
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ReviewedPlateFacts facts;
    private final SecureJsonClient es;private final String baseIndex,plateIndex,authorization,readerAuthorization;
    private final String owner="plate-index-"+UUID.randomUUID();
    private record Work(UUID observation,long revision,long generation){}
    public ReviewedPlatePublisher(JdbcTemplate db,PlatformTransactionManager manager,ReviewedPlateFacts facts,
            @Value("${VIDEO_ES_URL:}")String url,@Value("${VIDEO_ES_CA:}")String ca,
            @Value("${VIDEO_ES_INDEXER_USER:}")String user,@Value("${VIDEO_ES_INDEXER_PASSWORD:}")String password,
            @Value("${VIDEO_ES_INDEX:police-video-observations}")String index,
            @Value("${VIDEO_ES_USER:}")String reader,@Value("${VIDEO_ES_PASSWORD:}")String readerPassword){
        this.db=db;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);this.facts=facts;
        baseIndex=SecureJsonClient.validateIndex(index);plateIndex=indexName(index);es=new SecureJsonClient(url,ca);
        authorization="Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8));
        readerAuthorization="Basic "+Base64.getEncoder().encodeToString((reader+":"+readerPassword).getBytes(StandardCharsets.UTF_8));
    }
    public static String indexName(String observations){return SecureJsonClient.validateIndex(observations+"-plates");}
    private Optional<Work> claim(){
        return tx.execute(status->{
            var rows=db.queryForList("""
                SELECT observation_id,requested_revision,lease_generation,lease_owner
                FROM reviewed_plate_index_tasks WHERE requested_revision>published_revision AND attempts<?
                 AND next_attempt_at<=clock_timestamp() AND (lease_owner IS NULL OR lease_expires_at<=clock_timestamp())
                ORDER BY next_attempt_at,observation_id FOR UPDATE SKIP LOCKED LIMIT 1
                """,MAX_ATTEMPTS);
            if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();long generation=((Number)row.get("lease_generation")).longValue()+1;
            UUID observation=(UUID)row.get("observation_id");
            db.update("UPDATE reviewed_plate_index_tasks SET attempts=attempts+1,lease_owner=?,lease_generation=?,lease_expires_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp() WHERE observation_id=?",owner,generation,LEASE_SECONDS,observation);
            return Optional.of(new Work(observation,((Number)row.get("requested_revision")).longValue(),generation));
        });
    }
    public boolean processOne(){
        if(!facts.enabled())return false;var claimed=claim();if(claimed.isEmpty())return false;var work=claimed.get();
        try{
            var snapshot=facts.resolve(work.observation());Map<?,?> source=null;
            if("ready".equals(snapshot.outcome())){
                var stored=es.get("/"+baseIndex+"/_doc/"+work.observation(),readerAuthorization,Duration.ofSeconds(30));
                if(!(stored.get("_source") instanceof Map<?,?> document))throw new ReviewedPlateDocument.SourceChanged();
                source=document;
            }
            var document=ReviewedPlateDocument.build(snapshot,work.revision(),source);
            es.putVersioned(plateIndex,work.observation(),work.revision(),document,authorization);
            tx.executeWithoutResult(status->db.update("""
                UPDATE reviewed_plate_index_tasks SET published_revision=GREATEST(published_revision,?),source_record_version=?,evidence_sha256=?,fact_sha256=?,
                 projection_outcome=CASE WHEN requested_revision=? THEN ? ELSE 'pending' END,attempts=0,last_error_code=NULL,
                 lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
                WHERE observation_id=? AND lease_owner=? AND lease_generation=?
                """,work.revision(),snapshot.version(),snapshot.evidence(),snapshot.sha256(),work.revision(),snapshot.outcome(),work.observation(),owner,work.generation()));
        }catch(RuntimeException failure){
            String reason=failure instanceof ReviewedPlateDocument.SourceChanged?"base_document_changed":failure instanceof SecureJsonClient.UpstreamFailure?"search_backend_unavailable":"projection_source_unavailable";
            tx.executeWithoutResult(status->db.update("""
                UPDATE reviewed_plate_index_tasks SET attempts=CASE WHEN requested_revision=? THEN attempts ELSE 0 END,
                 last_error_code=?,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+(LEAST(300,5*power(2,LEAST(attempts,6))) * interval '1 second'),updated_at=clock_timestamp()
                WHERE observation_id=? AND lease_owner=? AND lease_generation=?
                """,work.revision(),reason,work.observation(),owner,work.generation()));
        }
        return true;
    }
    public Map<String,Object> status(){
        if(!facts.enabled())return Map.of("enabled",false);
        var counts=db.queryForMap("SELECT count(*) FILTER(WHERE requested_revision=published_revision AND projection_outcome='ready') AS indexed,count(*) FILTER(WHERE requested_revision>published_revision AND attempts<?) AS pending,count(*) FILTER(WHERE requested_revision>published_revision AND attempts>=?) AS failed,count(*) FILTER(WHERE requested_revision=published_revision AND projection_outcome='conflict') AS conflicts FROM reviewed_plate_index_tasks",MAX_ATTEMPTS,MAX_ATTEMPTS);
        var result=new LinkedHashMap<String,Object>(counts);result.put("enabled",true);return result;
    }
    public Map<String,Object> retryFailed(){
        if(!facts.enabled())throw new InputRejected(404,"reviewed_plate_search_disabled");
        int count=db.update("UPDATE reviewed_plate_index_tasks SET attempts=0,next_attempt_at=clock_timestamp(),last_error_code=NULL,lease_owner=NULL,lease_expires_at=NULL WHERE requested_revision>published_revision AND attempts>=? AND (lease_owner IS NULL OR lease_expires_at<=clock_timestamp())",MAX_ATTEMPTS);
        return Map.of("rescheduled",count);
    }
}
