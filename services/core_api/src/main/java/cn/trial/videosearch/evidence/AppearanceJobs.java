package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;

/** Durable bounded production/consumption, independent of the expensive analysis queue. */
@Service
public class AppearanceJobs {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FAILURES=Set.of("appearance_timeout","appearance_unavailable","appearance_source_changed","appearance_image_unavailable","appearance_vector_invalid","consumer_interrupted");
    private final JdbcTemplate db;private final AppearanceCatalog catalog;private final AppearanceSource sources;private final SearchOrchestrator search;
    private final TransactionTemplate tx;private final boolean enabled;private final int pendingLimit,activeLimit;
    public AppearanceJobs(JdbcTemplate db,AppearanceCatalog catalog,AppearanceSource sources,SearchOrchestrator search,PlatformTransactionManager manager,
        @Value("${VIDEO_APPEARANCE_ENABLED:false}")boolean enabled,@Value("${VIDEO_APPEARANCE_PENDING_LIMIT:500}")int pending,@Value("${VIDEO_APPEARANCE_ACTIVE_LIMIT:1}")int active){
        if(pending<1||pending>5000||active<1||active>4)throw new IllegalArgumentException("Invalid appearance limits");
        this.db=db;this.catalog=catalog;this.sources=sources;this.search=search;tx=new TransactionTemplate(manager);this.enabled=enabled;pendingLimit=pending;activeLimit=active;
    }
    void requireEnabled(){if(!enabled)throw new InputRejected(404,"appearance_disabled");}
    private void lock(){db.execute("SELECT pg_advisory_xact_lock(918273651)");}
    private boolean control(String name){return Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key=?",Boolean.class,name));}
    private void register(AppearanceCatalog.Profile profile){
        var rows=db.queryForList("SELECT definition_sha256 FROM appearance_profiles WHERE profile=?",profile.id());
        if(!rows.isEmpty()&&!profile.sha256().equals(rows.getFirst().get("definition_sha256")))throw new InputRejected(409,"appearance_profile_conflict");
        db.update("INSERT INTO appearance_profiles(profile,definition_sha256,definition) VALUES(?,?,?::jsonb) ON CONFLICT(profile) DO NOTHING",profile.id(),profile.sha256(),CanonicalJson.encode(profile.definition(),8192).payload());
    }
    public Map<String,Object> capabilities(){
        requireEnabled();var snapshot=catalog.current();var profile=snapshot.selected();
        var perType=new LinkedHashMap<String,Object>();var activeByType=new LinkedHashMap<String,String>();
        for(String type:List.of("person","vehicle")){
            var selected=snapshot.selected(type);activeByType.put(type,selected.id());
            perType.put(type,Map.of("profile",selected.definition(),"profile_sha256",selected.sha256()));
        }
        var stats=db.queryForList("""
            SELECT j.state,count(*) AS count FROM appearance_jobs j JOIN observations o ON o.observation_id=j.observation_id
            WHERE ((j.target_type='person' AND j.profile_sha256=?) OR (j.target_type='vehicle' AND j.profile_sha256=?)) AND NOT o.deleted AND o.record_version=j.record_version GROUP BY j.state
            """,snapshot.selected("person").sha256(),snapshot.selected("vehicle").sha256());var counts=new LinkedHashMap<String,Object>();stats.forEach(row->counts.put((String)row.get("state"),row.get("count")));
        var readable=search.readableCameras();var cameras=readable.isEmpty()?List.<Map<String,Object>>of():db.queryForList("SELECT camera_id,display_name FROM cameras WHERE camera_id IN ("+String.join(",",Collections.nCopies(readable.size(),"?"))+") ORDER BY display_name,camera_id",readable.toArray());
        var value=new LinkedHashMap<String,Object>();value.put("schema_version","appearance-capabilities-v1");value.put("profile",profile.definition());value.put("profile_sha256",profile.sha256());value.put("catalog_revision",snapshot.revision());value.put("index_backend","bounded_exact_cosine_v1");value.put("counts",counts);value.put("claims_enabled",control("appearance_claims"));value.put("production_enabled",control("appearance_production"));value.put("cameras",cameras);value.put("active_by_type",activeByType);value.put("profiles_by_type",perType);return value;
    }
    private int productionRound;
    public synchronized Map<String,Object> produce(int limit){return produce(limit,null);}
    public synchronized Map<String,Object> produce(int limit,List<String> requestedProfiles){
        requireEnabled();if(limit<1||limit>200)throw new IllegalArgumentException();var snapshot=catalog.current();
        var typesByProfile=new LinkedHashMap<String,List<String>>();
        if(requestedProfiles==null||requestedProfiles.isEmpty()){
            for(String type:List.of("person","vehicle"))typesByProfile.computeIfAbsent(snapshot.selected(type).id(),ignored->new ArrayList<>()).add(type);
        }else{
            if(requestedProfiles.size()>3||new HashSet<>(requestedProfiles).size()!=requestedProfiles.size())throw new IllegalArgumentException();
            for(String id:requestedProfiles){
                var selected=snapshot.profiles().get(id);if(selected==null)throw new InputRejected(422,"model_profile_unavailable");
                typesByProfile.put(id,List.of("person","vehicle").stream().filter(selected::supports).toList());
            }
        }
        var ids=new ArrayList<>(typesByProfile.keySet());Collections.rotate(ids,-Math.floorMod(productionRound++,ids.size()));
        var runs=new ArrayList<Map<String,Object>>();int queued=0,reused=0,skipped=0,scanned=0;boolean full=false,complete=true;
        for(int i=0;i<ids.size();i++){
            int budget=limit/ids.size()+(i<limit%ids.size()?1:0);if(budget==0){complete=false;continue;}
            String id=ids.get(i);var run=produceProfile(snapshot.profiles().get(id),typesByProfile.get(id),budget);runs.add(run);
            queued+=((Number)run.get("queued")).intValue();reused+=((Number)run.get("reused")).intValue();skipped+=((Number)run.get("skipped")).intValue();scanned+=((Number)run.get("scanned")).intValue();
            full|=Boolean.TRUE.equals(run.get("queue_full"));complete&=Boolean.TRUE.equals(run.get("scan_complete"));
        }
        var value=new LinkedHashMap<String,Object>();value.put("queued",queued);value.put("reused",reused);value.put("skipped",skipped);value.put("scanned",scanned);value.put("scan_complete",complete);value.put("queue_full",full);value.put("profile_sha256",snapshot.selected().sha256());value.put("profiles",runs);value.put("catalog_revision",snapshot.revision());return value;
    }
    private Map<String,Object> produceProfile(AppearanceCatalog.Profile profile,List<String> targetTypes,int limit){
        if(search.readableCameras().isEmpty())return Map.of("queued",0,"reused",0,"skipped",0,"scanned",0,"scan_complete",true,"queue_full",false,"profile_sha256",profile.sha256());
        tx.executeWithoutResult(status->{lock();register(profile);db.update("INSERT INTO appearance_scan_cursors(profile_sha256) VALUES(?) ON CONFLICT DO NOTHING",profile.sha256());});
        UUID after=db.queryForObject("SELECT last_observation_id FROM appearance_scan_cursors WHERE profile_sha256=?",UUID.class,profile.sha256());
        var args=new ArrayList<Object>(targetTypes);args.addAll(search.readableCameras());if(after!=null)args.add(after);args.add(limit);
        String cameras=String.join(",",Collections.nCopies(search.readableCameras().size(),"?"));
        var rows=db.queryForList("""
            SELECT o.observation_id,o.record_version FROM observations o JOIN frames f ON f.frame_id=o.frame_id
            JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id
            JOIN detected_targets d ON d.frame_id=f.frame_id AND d.observation_ordinal=o.object_ordinal
            WHERE NOT o.deleted AND f.analysis_state='completed' AND o.camera_id=s.camera_id AND o.target_type=d.object_type
            """+" AND o.target_type IN ("+String.join(",",Collections.nCopies(targetTypes.size(),"?"))+") AND o.camera_id IN ("+cameras+")"+(after==null?"":" AND o.observation_id>?")+" ORDER BY o.observation_id LIMIT ?",args.toArray());
        var prepared=new LinkedHashMap<UUID,Map<String,Object>>();int skipped=0;
        for(var row:rows){try{prepared.put((UUID)row.get("observation_id"),sources.current((UUID)row.get("observation_id"),((Number)row.get("record_version")).longValue()));}
            catch(InputRejected rejected){if(rejected.status!=404&&rejected.status!=409)throw rejected;skipped++;}}
        int unavailable=skipped;
        return tx.execute(status->{
            lock();UUID saved=db.queryForObject("SELECT last_observation_id FROM appearance_scan_cursors WHERE profile_sha256=? FOR UPDATE",UUID.class,profile.sha256());
            if(!Objects.equals(saved,after))throw new InputRejected(409,"appearance_scan_conflict");
            long room=pendingLimit-db.queryForObject("SELECT count(*) FROM appearance_jobs WHERE state IN ('queued','active')",Long.class);
            int queued=0,reused=0;UUID cursor=after;boolean full=false;
            for(var row:rows){UUID id=(UUID)row.get("observation_id");var descriptor=prepared.get(id);
                if(descriptor!=null){var encoded=CanonicalJson.encode(descriptor,16384);
                    var prior=db.queryForList("SELECT job_id FROM appearance_jobs WHERE profile_sha256=? AND source_sha256=?",profile.sha256(),encoded.sha256());
                    if(prior.isEmpty()){
                        if(room<=0){full=true;break;}
                        try{sources.verify(descriptor);}catch(InputRejected changed){if(changed.status!=404&&changed.status!=409)throw changed;cursor=id;continue;}
                        db.update("""
                            INSERT INTO appearance_jobs(job_id,observation_id,record_version,frame_id,camera_id,target_type,profile_sha256,source_sha256,descriptor)
                            VALUES(?,?,?,?,?,?,?,?,?::jsonb)
                            """,UUID.randomUUID(),id,row.get("record_version"),UUID.fromString((String)descriptor.get("frame_id")),descriptor.get("camera_id"),descriptor.get("target_type"),profile.sha256(),encoded.sha256(),encoded.payload());
                        queued++;room--;
                    }else reused++;
                }cursor=id;
            }
            boolean wrapped=!full&&rows.size()<limit;
            db.update("UPDATE appearance_scan_cursors SET last_observation_id=?,updated_at=clock_timestamp() WHERE profile_sha256=?",wrapped?null:cursor,profile.sha256());
            // Expiring query uploads were never stored; bounded cleanup removes only snapshots.
            db.update("DELETE FROM appearance_queries WHERE query_id IN (SELECT query_id FROM appearance_queries WHERE expires_at<clock_timestamp() ORDER BY expires_at LIMIT 200)");
            return Map.of("queued",queued,"reused",reused,"skipped",unavailable,"scanned",rows.size(),"scan_complete",wrapped,"queue_full",full,"profile_sha256",profile.sha256());
        });
    }
    public Map<String,Object> setControl(String name,boolean value){
        requireEnabled();if(name==null||!Set.of("appearance_claims","appearance_production").contains(name))throw new IllegalArgumentException();
        db.update("UPDATE processing_controls SET enabled=?,updated_at=clock_timestamp() WHERE control_key=?",value,name);return Map.of("control",name,"enabled",value);
    }
    public void scheduledProduce(){if(enabled&&control("appearance_production"))produce(100);}
    private static void lease(String worker,int seconds){if(worker==null||!worker.matches("[A-Za-z0-9_.-]{1,70}")||seconds<60||seconds>600)throw new IllegalArgumentException();}
    public Map<String,Object> claim(String worker,int seconds,List<String> profiles){
        requireEnabled();lease(worker,seconds);
        if(profiles==null||profiles.isEmpty()||profiles.size()>8||profiles.stream().anyMatch(value->!AppearanceCatalog.hex(value))||new HashSet<>(profiles).size()!=profiles.size())throw new IllegalArgumentException();
        return tx.execute(status->{lock();
            if(!control("appearance_claims"))return Map.of("job_available",false,"paused",true);
            db.update("UPDATE appearance_jobs SET state='failed',last_error_code='appearance_timeout',lease_expires_at=NULL,updated_at=clock_timestamp() WHERE state='active' AND lease_expires_at<=clock_timestamp() AND attempts>=3");
            if(db.queryForObject("SELECT count(*) FROM appearance_jobs WHERE state='active' AND lease_expires_at>clock_timestamp()",Long.class)>=activeLimit)return Map.of("job_available",false);
            var args=new ArrayList<Object>(profiles);String supported=String.join(",",Collections.nCopies(profiles.size(),"?"));
            var rows=db.queryForList("SELECT job_id FROM appearance_jobs WHERE profile_sha256 IN ("+supported+") AND attempts<3 AND ((state='queued' AND next_attempt_at<=clock_timestamp()) OR (state='active' AND lease_expires_at<=clock_timestamp())) ORDER BY created_at,job_id LIMIT 1 FOR UPDATE SKIP LOCKED",args.toArray());
            if(rows.isEmpty())return Map.of("job_available",false);
            UUID id=(UUID)rows.getFirst().get("job_id");
            db.update("UPDATE appearance_jobs SET state='active',attempts=attempts+1,lease_owner=?,lease_generation=lease_generation+1,lease_expires_at=clock_timestamp()+?*interval '1 second',updated_at=clock_timestamp() WHERE job_id=?",worker,seconds,id);
            var job=row(id,false);var value=new LinkedHashMap<String,Object>();
            for(String key:List.of("job_id","lease_generation","source_sha256","profile_sha256"))value.put(key,Objects.toString(job.get(key)));
            value.put("lease_generation",job.get("lease_generation"));value.put("descriptor",parse(job,"descriptor_text"));value.put("definition",parse(job,"definition_text"));value.put("lease_seconds",seconds);
            return Map.of("job_available",true,"job",value);
        });
    }
    private Map<String,Object> row(UUID job,boolean locked){
        var rows=db.queryForList("SELECT j.*,j.descriptor::text AS descriptor_text,p.definition::text AS definition_text,j.lease_expires_at>clock_timestamp() AS alive FROM appearance_jobs j JOIN appearance_profiles p ON p.definition_sha256=j.profile_sha256 WHERE j.job_id=?"+(locked?" FOR UPDATE OF j":""),job);
        if(rows.size()!=1)throw new InputRejected(404,"appearance_job_unavailable");return rows.getFirst();
    }
    private static Map<String,Object> parse(Map<String,Object> row,String key){return AppearanceCatalog.object(JSON.readValue((String)row.get(key),Object.class));}
    private Map<String,Object> owned(UUID id,String worker,long generation,boolean locked,boolean completed){
        lease(worker,60);var row=row(id,locked);
        if(generation<1||!worker.equals(row.get("lease_owner"))||generation!=((Number)row.get("lease_generation")).longValue()
            ||!("active".equals(row.get("state"))&&Boolean.TRUE.equals(row.get("alive"))||completed&&"completed".equals(row.get("state"))))throw new InputRejected(409,"appearance_lease_expired");
        return row;
    }
    public Map<String,Object> renew(UUID id,String worker,long generation,int seconds){
        requireEnabled();lease(worker,seconds);return tx.execute(status->{var row=owned(id,worker,generation,true,false);db.update("UPDATE appearance_jobs SET lease_expires_at=clock_timestamp()+?*interval '1 second',updated_at=clock_timestamp() WHERE job_id=?",seconds,id);return Map.of("renewed",true);});
    }
    public EvidenceFiles.FrameFile image(UUID id,String worker,long generation){
        requireEnabled();var row=owned(id,worker,generation,false,false);var image=sources.image(parse(row,"descriptor_text"));owned(id,worker,generation,false,false);return image;
    }
    public Map<String,Object> fail(UUID id,String worker,long generation,String code){
        requireEnabled();if(code==null||!FAILURES.contains(code))throw new IllegalArgumentException();
        return tx.execute(status->{lock();var row=owned(id,worker,generation,true,false);int attempts=((Number)row.get("attempts")).intValue();
            String state=code.equals("appearance_source_changed")?"invalid":attempts>=3?"failed":"queued";
            db.update("UPDATE appearance_jobs SET state=?,last_error_code=?,lease_expires_at=NULL,next_attempt_at=clock_timestamp()+?*interval '1 second',updated_at=clock_timestamp() WHERE job_id=?",state,code,5*(1<<(attempts-1)),id);return Map.of("recorded",true,"state",state);
        });
    }
    public record Complete(UUID jobId,String workerId,long generation,String sourceSha256,String profileSha256,List<Double> vector){}
    public Map<String,Object> complete(Complete body){
        requireEnabled();if(body==null||body.jobId()==null)throw new IllegalArgumentException();var row=owned(body.jobId(),body.workerId(),body.generation(),false,true);
        var profile=AppearanceCatalog.parse(parse(row,"definition_text"));AppearanceVectors.validate(body.vector(),profile.dimensions());
        if(!profile.supports((String)row.get("target_type")))throw new InputRejected(422,"appearance_target_type_unsupported");
        if(!row.get("source_sha256").equals(body.sourceSha256())||!row.get("profile_sha256").equals(body.profileSha256()))throw new InputRejected(409,"appearance_source_changed");
        var descriptor=parse(row,"descriptor_text");sources.image(descriptor);
        String sha=CanonicalJson.encode(body.vector(),32768).sha256();
        return tx.execute(status->{lock();var locked=owned(body.jobId(),body.workerId(),body.generation(),true,true);sources.verify(descriptor);
            var previous=db.queryForList("SELECT vector_sha256 FROM appearance_features WHERE job_id=?",body.jobId());
            if(!previous.isEmpty()){if(!sha.equals(previous.getFirst().get("vector_sha256")))throw new InputRejected(409,"appearance_result_conflict");return Map.of("completed",true,"replayed",true);}
            if(!"active".equals(locked.get("state")))throw new InputRejected(409,"appearance_result_conflict");
            db.update(connection->{var statement=connection.prepareStatement("INSERT INTO appearance_features(job_id,vector,vector_sha256) VALUES(?,?,?)");statement.setObject(1,body.jobId());statement.setArray(2,connection.createArrayOf("real",body.vector().toArray()));statement.setString(3,sha);return statement;});
            db.update("UPDATE appearance_jobs SET state='completed',lease_expires_at=NULL,last_error_code=NULL,updated_at=clock_timestamp() WHERE job_id=?",body.jobId());return Map.of("completed",true,"replayed",false);
        });
    }
    public Map<String,Object> retry(UUID id){
        requireEnabled();return tx.execute(status->{lock();var row=row(id,true);if(!search.mayReadCamera((String)row.get("camera_id")))throw new InputRejected(404,"appearance_job_unavailable");sources.verify(parse(row,"descriptor_text"));
            if(!Set.of("failed","invalid").contains(row.get("state")))throw new InputRejected(409,"appearance_retry_conflict");
            if(db.queryForObject("SELECT count(*) FROM appearance_jobs WHERE state IN ('queued','active')",Long.class)>=pendingLimit)throw new InputRejected(429,"appearance_queue_full");
            db.update("UPDATE appearance_jobs SET state='queued',attempts=0,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE job_id=?",id);return Map.of("queued",true);
        });
    }
    public Map<String,Object> list(int limit,UUID after){
        requireEnabled();if(limit<1||limit>50)throw new IllegalArgumentException();String filter=" WHERE camera_id IN ("+String.join(",",Collections.nCopies(search.readableCameras().size(),"?"))+")";
        var args=new ArrayList<Object>(search.readableCameras());if(after!=null){filter+=" AND job_id>?";args.add(after);}args.add(limit+1);
        var rows=db.queryForList("SELECT job_id,observation_id,frame_id,camera_id,target_type,record_version,profile_sha256,state,attempts,last_error_code,created_at,updated_at FROM appearance_jobs"+filter+" ORDER BY job_id LIMIT ?",args.toArray());
        boolean more=rows.size()>limit;var items=rows.subList(0,Math.min(limit,rows.size())).stream().map(row->{var value=new LinkedHashMap<String,Object>();row.forEach((key,entry)->value.put(key,entry instanceof UUID?entry.toString():entry instanceof Timestamp time?time.toInstant().toString():entry));return value;}).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("has_more",more);result.put("next_cursor",more?rows.get(limit-1).get("job_id").toString():null);return result;
    }
}
