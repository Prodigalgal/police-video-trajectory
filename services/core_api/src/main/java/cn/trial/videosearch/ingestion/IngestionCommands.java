package cn.trial.videosearch.ingestion;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class IngestionCommands {
    public record Item(UUID sourceId,long startMs,Long endMs,int priority){}
    public record CreateBatch(UUID requestId,String title,List<Item> items){}
    public record Control(UUID requestId,long expectedRevision,String action,String reason){}
    public record SourceCreate(UUID requestId,String cameraId,String sourceType,String sourceRef){}
    public record SourceToggle(UUID requestId,String expectedRevision,boolean enabled,String reason){}
    public record GlobalControl(UUID requestId,String expectedRevision,boolean enabled,String reason){}
    private final JdbcTemplate db;
    private final IngestionQueries queries;
    private final IngestionPolicy policy;
    private final Map<String,SourceInputAdapter> adapters;
    private final TransactionTemplate tx;
    private static final ObjectMapper JSON=new ObjectMapper();
    @SuppressWarnings("unchecked") private static Map<String,Object> snapshot(String payload){return JSON.readValue(payload,Map.class);}
    public IngestionCommands(JdbcTemplate db,IngestionQueries queries,IngestionPolicy policy,List<SourceInputAdapter> adapters,PlatformTransactionManager manager){this.db=db;this.queries=queries;this.policy=policy;this.adapters=adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(SourceInputAdapter::type,java.util.function.Function.identity()));tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    private void lock(){db.execute("SELECT pg_advisory_xact_lock(918273692)");policy.lock(db);}
    public static UUID identity(UUID request,String label){return UUID.nameUUIDFromBytes((request+":"+label).getBytes(StandardCharsets.UTF_8));}
    private static String text(String value,int bound){if(value==null||value.isBlank()||value.length()>bound||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid ingestion text");return value.strip();}
    @SuppressWarnings("unchecked") private Map<String,Object> previous(UUID request,String operation,String actor,String sha){
        var rows=db.queryForList("SELECT operation,actor_id,request_sha256,response::text AS payload FROM application_command_receipts WHERE request_id=?",request);
        if(rows.isEmpty())return null;var row=rows.getFirst();
        if(!operation.equals(row.get("operation"))||!actor.equals(row.get("actor_id"))||!sha.equals(row.get("request_sha256")))throw new AdminService.Rejected(409,"command_request_conflict");
        return JSON.readValue((String)row.get("payload"),Map.class);
    }
    private Map<String,Object> save(UUID request,String operation,String actor,String sha,Map<String,Object> response){
        db.update("INSERT INTO application_command_receipts(request_id,operation,actor_id,request_sha256,response) VALUES(?,?,?,?,?::jsonb)",request,operation,actor,sha,CanonicalJson.encode(response,65536).payload());
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,operation,CanonicalJson.encode(response,65536).payload(),request);return response;
    }
    private SourceInputAdapter adapter(String type){if(type==null)throw new IllegalArgumentException("Source type required");var adapter=adapters.get(type);if(adapter==null)throw new AdminService.Rejected(422,"source_adapter_unavailable");return adapter;}
    public Map<String,Object> register(SourceCreate body,String actor){
        queries.requireEnabled();if(body==null||body.requestId()==null||body.cameraId()==null||body.sourceRef()==null)throw new IllegalArgumentException();queries.camera(body.cameraId());adapter(body.sourceType()).validate(body.cameraId(),body.sourceRef());
        String sha=CanonicalJson.encode(Map.of("camera",body.cameraId(),"type",body.sourceType(),"ref",body.sourceRef()),8192).sha256();
        return tx.execute(status->{lock();var replay=previous(body.requestId(),"ingestion.source.register",actor,sha);if(replay!=null)return replay;
            UUID id=identity(body.requestId(),"source");
            var rows=db.queryForList("INSERT INTO sources(source_id,camera_id,source_type,source_ref,source_time_origin,enabled) VALUES(?,?,?,?,'unknown',false) ON CONFLICT(camera_id,source_ref) DO UPDATE SET source_ref=excluded.source_ref WHERE sources.source_type=excluded.source_type RETURNING source_id",id,body.cameraId(),body.sourceType(),body.sourceRef());
            if(rows.isEmpty())throw new AdminService.Rejected(409,"ingestion_source_changed");
            return save(body.requestId(),"ingestion.source.register",actor,sha,Map.of("source_id",rows.getFirst().get("source_id").toString(),"camera_id",body.cameraId(),"source_type",body.sourceType()));
        });
    }
    public Map<String,Object> create(CreateBatch body,String actor){
        queries.requireEnabled();if(body==null||body.requestId()==null||body.items()==null||body.items().isEmpty()||body.items().size()>12)throw new IllegalArgumentException();String title=text(body.title(),160);
        var descriptors=new ArrayList<Map<String,Object>>();var sources=new LinkedHashMap<UUID,Map<String,Object>>();var unique=new HashSet<String>();
        for(var item:body.items()){
            if(item==null||item.sourceId()==null||item.startMs()<0||item.startMs()>604800000L||item.endMs()!=null&&(item.endMs()<=item.startMs()||item.endMs()>604800000L)||item.priority()<0||item.priority()>9||!unique.add(item.sourceId()+":"+item.startMs()+":"+item.endMs()))throw new IllegalArgumentException();
            var source=queries.source(item.sourceId(),false);adapter((String)source.get("source_type")).validate((String)source.get("camera_id"),(String)source.get("source_ref"));
            if("rtsp".equals(source.get("source_type"))&&(item.startMs()!=0||item.endMs()!=null))throw new IllegalArgumentException("Live input cannot have an archive window");
            sources.put(item.sourceId(),source);var descriptor=new LinkedHashMap<String,Object>();descriptor.put("source_id",item.sourceId().toString());descriptor.put("start_ms",item.startMs());descriptor.put("end_ms",item.endMs());descriptor.put("priority",item.priority());descriptors.add(descriptor);
        }
        String sha=CanonicalJson.encode(Map.of("title",title,"items",descriptors),65536).sha256();var replay=previous(body.requestId(),"ingestion.batch.create",actor,sha);if(replay!=null)return replay;
        // Full-file inspection is deliberately outside the short command transaction.
        var snapshots=new HashMap<UUID,CanonicalJson.Encoded>();for(var entry:sources.entrySet())snapshots.put(entry.getKey(),CanonicalJson.encode(adapter((String)entry.getValue().get("source_type")).snapshot(IngestionQueries.definition(entry.getValue())),16384));
        return tx.execute(status->{lock();var prior=previous(body.requestId(),"ingestion.batch.create",actor,sha);if(prior!=null)return prior;
            for(var source:sources.entrySet())if(!IngestionQueries.definition(source.getValue()).equals(IngestionQueries.definition(queries.source(source.getKey(),true))))throw new AdminService.Rejected(409,"ingestion_source_changed");
            UUID batch=identity(body.requestId(),"batch");db.update("INSERT INTO ingestion_batches(batch_id,request_id,actor_id,request_sha256,title) VALUES(?,?,?,?,?)",batch,body.requestId(),actor,sha,title);var tasks=new ArrayList<String>();
            for(int index=0;index<body.items().size();index++){
                var item=body.items().get(index);var source=sources.get(item.sourceId());var base=snapshots.get(item.sourceId());
                var input=new LinkedHashMap<>(snapshot(base.payload()));var window=new LinkedHashMap<String,Object>();window.put("start_ms",item.startMs());window.put("end_ms",item.endMs());input.put("window",window);var snapshot=CanonicalJson.encode(input,16384);UUID task=identity(body.requestId(),"task-"+index);
                db.update("INSERT INTO processing_tasks(task_id,source_id,task_type,state) VALUES(?,?,?,'queued')",task,item.sourceId(),source.get("source_type"));
                db.update("INSERT INTO ingestion_task_specs(task_id,batch_id,input_contract,input_kind,source_snapshot,input_sha256,start_ms,end_ms,priority) VALUES(?,?,'managed-ingestion-v1',?,?::jsonb,?,?,?,?)",task,batch,adapter((String)source.get("source_type")).kind(),snapshot.payload(),snapshot.sha256(),item.startMs(),item.endMs(),item.priority());tasks.add(task.toString());
            }
            return save(body.requestId(),"ingestion.batch.create",actor,sha,Map.of("batch_id",batch.toString(),"task_ids",tasks,"title",title));
        });
    }
    public Map<String,Object> control(UUID id,Control body,String actor){
        queries.requireEnabled();if(body==null||body.requestId()==null||body.expectedRevision()<1||body.action()==null||!Set.of("pause","resume","retry").contains(body.action()))throw new IllegalArgumentException();String reason=text(body.reason(),300);queries.task(id,false);
        String sha=CanonicalJson.encode(Map.of("task",id.toString(),"revision",body.expectedRevision(),"action",body.action(),"reason",reason),8192).sha256();
        return tx.execute(status->{lock();queries.task(id,false);var prior=previous(body.requestId(),"ingestion.task.control",actor,sha);if(prior!=null)return prior;var task=queries.task(id,true);
            if(((Number)task.get("revision")).longValue()!=body.expectedRevision())throw new AdminService.Rejected(409,"ingestion_revision_conflict");
            boolean pause=body.action().equals("pause");String state=(String)task.get("state");
            if(pause&&Set.of("completed","disabled").contains(state)||body.action().equals("resume")&&!"disabled".equals(state)||body.action().equals("retry")&&!"failed".equals(state))throw new AdminService.Rejected(409,"ingestion_state_conflict");
            db.update("UPDATE processing_tasks SET state=?,lease_generation=lease_generation+1,lease_owner=NULL,lease_expires_at=NULL,last_error_code=NULL,updated_at=clock_timestamp() WHERE task_id=?",pause?"disabled":"queued",id);
            db.update("UPDATE ingestion_task_specs SET paused=?,revision=revision+1,failures=CASE WHEN ? THEN 0 ELSE failures END,reader_finished=false,last_error_code=NULL,next_attempt_at=clock_timestamp() WHERE task_id=?",pause,body.action().equals("retry"),id);
            return save(body.requestId(),"ingestion.task.control",actor,sha,Map.of("task_id",id.toString(),"revision",body.expectedRevision()+1,"state",pause?"disabled":"queued","reason",reason));
        });
    }
    public Map<String,Object> toggle(UUID id,SourceToggle body,String actor){
        queries.requireEnabled();if(body==null||body.requestId()==null||body.expectedRevision()==null)throw new IllegalArgumentException();String reason=text(body.reason(),300);queries.source(id,false);
        String sha=CanonicalJson.encode(Map.of("source",id.toString(),"revision",body.expectedRevision(),"enabled",body.enabled(),"reason",reason),8192).sha256();
        return tx.execute(status->{lock();var source=queries.source(id,true);var prior=previous(body.requestId(),"ingestion.source.toggle",actor,sha);if(prior!=null)return prior;
            if(!sourceRevision(source).equals(body.expectedRevision()))throw new AdminService.Rejected(409,"ingestion_revision_conflict");
            if(body.enabled()&&!Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM processing_controls WHERE control_key='analysis_claims'",Boolean.class)))throw new AdminService.Rejected(409,"processing_paused");
            db.update("UPDATE sources SET enabled=? WHERE source_id=?",body.enabled(),id);
            return save(body.requestId(),"ingestion.source.toggle",actor,sha,Map.of("source_id",id.toString(),"enabled",body.enabled(),"reason",reason));
        });
    }
    public static String sourceRevision(Map<String,Object> source){return CanonicalJson.encode(Map.of("definition",IngestionQueries.definition(source),"enabled",source.get("enabled")),8192).sha256();}
    public Map<String,Object> global(){queries.requireEnabled();var row=db.queryForList("SELECT enabled,updated_at FROM processing_controls WHERE control_key='analysis_claims'").getFirst();var view=IngestionQueries.external(row);return Map.of("enabled",row.get("enabled"),"revision",CanonicalJson.encode(view,8192).sha256(),"policy",policy.view());}
    public Map<String,Object> global(GlobalControl body,String actor){
        queries.requireEnabled();if(body==null||body.requestId()==null||body.expectedRevision()==null)throw new IllegalArgumentException();String reason=text(body.reason(),300);
        String sha=CanonicalJson.encode(Map.of("revision",body.expectedRevision(),"enabled",body.enabled(),"reason",reason),8192).sha256();
        return tx.execute(status->{lock();var prior=previous(body.requestId(),"ingestion.global.control",actor,sha);if(prior!=null)return prior;
            db.queryForList("SELECT control_key FROM processing_controls WHERE control_key='analysis_claims' FOR UPDATE");
            if(!global().get("revision").equals(body.expectedRevision()))throw new AdminService.Rejected(409,"ingestion_revision_conflict");
            db.update("UPDATE processing_controls SET enabled=?,updated_at=clock_timestamp() WHERE control_key='analysis_claims'",body.enabled());
            var response=new LinkedHashMap<>(global());response.put("reason",reason);return save(body.requestId(),"ingestion.global.control",actor,sha,response);
        });
    }
}
