package cn.trial.videosearch.clues;

import cn.trial.videosearch.CoreApplication;
import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.evidence.ObservationDetails;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Operator-invoked real PostgreSQL check. All writes are rolled back in an isolated schema. */
public class ClueDatabaseCheck {
    static void check(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var app=new SpringApplication(CoreApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("spring.main.banner-mode","off","logging.level.root","WARN","spring.datasource.hikari.maximum-pool-size","2"));
        try(var context=app.run()) {
            var db=context.getBean(JdbcTemplate.class);var service=context.getBean(ClueCollections.class);var evidence=context.getBean(ObservationDetails.class);
            var transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(120);
            var result=transaction.execute(status->{
                try {
                    var cases=json.readValue(Files.readString(Path.of(args[0]).resolve("cases.json")),List.class);
                    var references=new ArrayList<String>();
                    for(Object raw:cases)references.add("'"+UUID.fromString((String)((Map<String,Object>)raw).get("observation_id"))+"'::uuid");
                    // Copy retained real records into connection-local tables. No role privileges are broadened.
                    db.execute("CREATE TEMP TABLE observations AS SELECT * FROM public.observations WHERE observation_id IN ("+String.join(",",references)+")");
                    db.execute("ALTER TABLE observations ADD PRIMARY KEY(observation_id)");
                    db.execute("CREATE TEMP TABLE frames AS SELECT f.* FROM public.frames f WHERE f.frame_id IN (SELECT frame_id FROM observations)");
                    db.execute("CREATE TEMP TABLE processing_tasks AS SELECT t.* FROM public.processing_tasks t WHERE t.task_id IN (SELECT task_id FROM frames)");
                    db.execute("CREATE TEMP TABLE sources AS SELECT s.* FROM public.sources s WHERE s.source_id IN (SELECT source_id FROM processing_tasks)");
                    db.execute("CREATE TEMP TABLE cameras AS SELECT c.* FROM public.cameras c WHERE c.camera_id IN (SELECT camera_id FROM observations)");
                    db.execute("CREATE TEMP TABLE capture_outbox AS SELECT c.* FROM public.capture_outbox c WHERE c.frame_id IN (SELECT frame_id FROM frames)");
                    db.execute("CREATE TEMP TABLE detected_targets AS SELECT d.* FROM public.detected_targets d WHERE d.frame_id IN (SELECT frame_id FROM frames)");
                    db.execute("CREATE TEMP TABLE audit_events (LIKE public.audit_events INCLUDING ALL)");
                    db.execute(Files.readString(Path.of(args[0]).resolve("clue-migration.sql")).replace("CREATE TABLE IF NOT EXISTS","CREATE TEMP TABLE IF NOT EXISTS"));
                    UUID id=UUID.randomUUID();var creation=new ClueCollections.Create(id,"实际点位线索核验","=SUM(A1)\n事务回滚后不留业务测试记录");
                    var value=service.create(creation,"admin");long revision=revision(value);
                    check(revision==1,"creation revision");check(revision(service.create(creation,"admin"))==1,"idempotent create");
                    var ids=new ArrayList<UUID>();Map<String,Object> firstDetail=null;
                    for(Object raw:cases) {
                        var item=(Map<String,Object>)raw;UUID observed=UUID.fromString((String)item.get("observation_id"));ids.add(observed);
                        var detail=evidence.read(observed).orElseThrow();if(firstDetail==null)firstDetail=detail;
                        value=service.add(id,observed,new ClueCollections.Add(revision,((Number)detail.get("record_version")).longValue()),"admin");revision=revision(value);
                    }
                    check(((List<?>)value.get("nodes")).size()==cases.size(),"real node count");check("collection_order".equals(value.get("timeline_order")),"unverified clocks never fabricate chronological trajectory");
                    var image=(Map<String,Object>)firstDetail.get("evidence");long recordVersion=((Number)firstDetail.get("record_version")).longValue();
                    value=service.review(id,ids.getFirst(),new ClueCollections.Review(revision,recordVersion,(String)image.get("sha256"),"retained","原始点位画面核验"),"admin");revision=revision(value);
                    var nodes=(List<Map<String,Object>>)value.get("nodes");var reviewed=nodes.stream().filter(node->ids.getFirst().equals(node.get("observation_id"))).findFirst().orElseThrow();
                    check("retained".equals(reviewed.get("review_state")) && Boolean.FALSE.equals(reviewed.get("review_stale")),"review evidence binding");
                    value=service.link(id,ids.get(0),ids.get(1),new ClueCollections.Link(revision,"仅候选对照，不断言同一身份","pending"),"admin");revision=revision(value);
                    check(((List<?>)value.get("links")).size()==1 && "candidate_only".equals(value.get("association_kind")),"candidate relation");
                    var exported=service.export(id,revision,"json","admin");var csv=service.export(id,revision,"csv","admin");
                    check(json.readValue(exported.bytes(),Map.class).get("schema_version").equals("clue-collection-v1"),"JSON export");
                    check(new String(csv.bytes(),java.nio.charset.StandardCharsets.UTF_8).startsWith("\ufeffobservation_id"),"CSV export");
                    // Change only the connection-local copies to test later model revisions and withdrawal.
                    db.update("UPDATE observations SET record_version=record_version+1 WHERE observation_id=?",ids.getFirst());
                    var revised=service.read(id);var revisedNodes=(List<Map<String,Object>>)revised.get("nodes");
                    check(Boolean.TRUE.equals(revisedNodes.stream().filter(node->ids.getFirst().equals(node.get("observation_id"))).findFirst().orElseThrow().get("review_stale")),"old review flagged after record update");
                    check(Boolean.TRUE.equals(((List<Map<String,Object>>)revised.get("links")).getFirst().get("review_stale")),"old relation basis flagged");
                    db.update("UPDATE observations SET deleted=true WHERE observation_id=?",ids.get(1));
                    var withdrawn=(List<Map<String,Object>>)service.read(id).get("nodes");var hidden=withdrawn.stream().filter(node->ids.get(1).equals(node.get("observation_id"))).findFirst().orElseThrow();
                    check(Boolean.FALSE.equals(hidden.get("available")) && !hidden.containsKey("description") && !hidden.containsKey("frame_id"),"withdrawn raw evidence hidden");
                    value=service.edit(id,new ClueCollections.Edit("已归档核验清单","", "closed",revision),"admin");long closedRevision=revision(value);
                    try {service.add(id,ids.getFirst(),new ClueCollections.Add(closedRevision,recordVersion),"admin");throw new IllegalStateException("closed collection allowed a mutation");}
                    catch(AdminService.Rejected expected){check(expected.code.equals("collection_closed"),"closed guard");}
                    check(db.queryForObject("SELECT count(*) FROM audit_events",Long.class)>8,"isolated audit records");
                    status.setRollbackOnly();
                    var report=new LinkedHashMap<String,Object>();report.put("result","passed");report.put("real_points",4);report.put("retained_observations",cases.size());report.put("idempotent_create",true);report.put("jpeg_bound_review",true);report.put("candidate_only_links",true);report.put("exports",List.of("json","csv"));report.put("closed_mutation_blocked",true);report.put("stale_review_and_relation_flagged",true);report.put("withdrawn_evidence_hidden",true);report.put("transaction_rollback",true);report.put("new_model_requests",0);return report;
                }catch(Exception error){throw new IllegalStateException("isolated collection check failed",error);}
            });
            check(db.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname IN ('clue_collections','clue_collection_items','clue_candidate_links','observations','audit_events')",Long.class)==0,"temporary business tables survived rollback");
            System.out.println("CLUE_CHECK_RESULT="+json.writeValueAsString(result));
        }
    }
    @SuppressWarnings("unchecked") static long revision(Map<String,Object> value){return ((Number)((Map<String,Object>)value.get("collection")).get("revision")).longValue();}
}
