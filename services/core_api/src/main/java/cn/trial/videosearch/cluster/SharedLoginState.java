package cn.trial.videosearch.cluster;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SharedLoginState {
    public record Issue(String challengeId,String peerKey,Map<String,Object> payload){}
    public record Consume(String challengeId,String failureKey){}
    public record Clear(String failureKey){}
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    private final JdbcTemplate db;private final boolean enabled;
    public SharedLoginState(JdbcTemplate db,@Value("${VIDEO_CLUSTER_COORDINATION_ENABLED:false}")boolean enabled){this.db=db;this.enabled=enabled;}
    public boolean ready(){
        return enabled&&Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('cluster_login_challenges') IS NOT NULL AND to_regclass('cluster_login_limits') IS NOT NULL AND to_regclass('cluster_model_leases') IS NOT NULL",Boolean.class));
    }
    private void lock(){
        if(!enabled)throw new InputRejected(503,"cluster_coordination_disabled");
        db.execute("SELECT pg_advisory_xact_lock(918273697)");
        db.update("DELETE FROM cluster_login_challenges WHERE expires_at<=clock_timestamp()");
        db.update("DELETE FROM cluster_login_limits WHERE expires_at<=clock_timestamp()");
    }
    private static void digest(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid coordination digest");}
    private static void challenge(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{32,64}"))throw new IllegalArgumentException("Invalid challenge identity");}
    private void limit(String kind,String key,int maximum){
        long now=Objects.requireNonNull(db.queryForObject("SELECT (extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class));
        var rows=db.queryForList("SELECT attempts::text AS attempts FROM cluster_login_limits WHERE kind=? AND key_sha256=? FOR UPDATE",kind,key);
        var attempts=new ArrayList<Long>();
        if(!rows.isEmpty())for(Object value:JSON.readValue((String)rows.getFirst().get("attempts"),List.class)){
            long instant=((Number)value).longValue();if(instant>now-60_000)attempts.add(instant);
        }
        if(attempts.size()>=maximum)throw new InputRejected(429,"login_rate_limited");
        if(rows.isEmpty()&&db.queryForObject("SELECT count(*) FROM cluster_login_limits WHERE kind=?",Long.class,kind)>=2048)throw new InputRejected(429,"login_rate_limited");
        attempts.add(now);
        db.update("INSERT INTO cluster_login_limits(kind,key_sha256,attempts,expires_at) VALUES(?,?,?::jsonb,clock_timestamp()+interval '2 minutes') ON CONFLICT(kind,key_sha256) DO UPDATE SET attempts=excluded.attempts,expires_at=excluded.expires_at",kind,key,CanonicalJson.encode(attempts,2048).payload());
    }
    @Transactional(timeout=5)
    public Map<String,Object> issue(Issue body){
        if(body==null)throw new IllegalArgumentException();challenge(body.challengeId());digest(body.peerKey());
        var payload=body.payload();
        if(payload==null||!payload.keySet().equals(Set.of("answer_digest","cookie","revision","bits")))throw new IllegalArgumentException();
        for(String key:List.of("answer_digest","cookie","revision")){if(!(payload.get(key) instanceof String text))throw new IllegalArgumentException();digest(text);}
        if(!(payload.get("bits") instanceof Integer bits)||bits<16||bits>22)throw new IllegalArgumentException();
        String encoded=CanonicalJson.encode(payload,2048).payload();lock();limit("issue",body.peerKey(),30);
        if(db.queryForObject("SELECT count(*) FROM cluster_login_challenges",Long.class)>=2048)throw new InputRejected(429,"login_rate_limited");
        if(db.update("INSERT INTO cluster_login_challenges(challenge_id,payload) VALUES(?,?::jsonb) ON CONFLICT DO NOTHING",body.challengeId(),encoded)!=1)throw new InputRejected(409,"login_challenge_conflict");
        return Map.of("accepted",true);
    }
    @Transactional(timeout=5)
    public Map<String,Object> consume(Consume body){
        if(body==null)throw new IllegalArgumentException();digest(body.failureKey());
        // Invalid or missing client proofs still consume a failure allowance.
        lock();limit("login",body.failureKey(),5);
        if(body.challengeId()==null||!body.challengeId().matches("[A-Za-z0-9_-]{32,64}"))return Map.of("available",false);
        var rows=db.queryForList("DELETE FROM cluster_login_challenges WHERE challenge_id=? AND expires_at>clock_timestamp() RETURNING payload::text AS payload",body.challengeId());
        return rows.isEmpty()?Map.of("available",false):Map.of("available",true,"payload",JSON.readValue((String)rows.getFirst().get("payload"),Map.class));
    }
    @Transactional(timeout=5)
    public Map<String,Object> clear(Clear body){
        if(body==null)throw new IllegalArgumentException();digest(body.failureKey());lock();
        db.update("DELETE FROM cluster_login_limits WHERE kind='login' AND key_sha256=?",body.failureKey());return Map.of("cleared",true);
    }
}
