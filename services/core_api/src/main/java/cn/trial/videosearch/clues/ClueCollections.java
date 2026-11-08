package cn.trial.videosearch.clues;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.evidence.ObservationDetails;
import cn.trial.videosearch.ingest.ObservationStore;
import cn.trial.videosearch.search.SearchCursorCodec;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Shared investigation workspaces. A candidate relation is never an identity assertion. */
@Service
public class ClueCollections {
    public record Create(UUID requestId,String title,String note) {}
    public record Edit(String title,String note,String state,long expectedRevision) {}
    public record Add(long expectedRevision,long observationVersion) {}
    public record Review(long expectedRevision,long observationVersion,String evidenceSha256,String state,String note) {}
    public record Link(long expectedRevision,String reason,String state) {}
    public record Export(byte[] bytes,String sha256,String mediaType,String filename) {}
    private final JdbcTemplate db;private final SearchOrchestrator search;private final ObservationDetails details;
    private final TransactionTemplate transactions;
    private static final ObjectMapper JSON=new ObjectMapper();
    public ClueCollections(JdbcTemplate db,SearchOrchestrator search,ObservationDetails details,PlatformTransactionManager transactionManager) {
        this.db=db;this.search=search;this.details=details;this.transactions=new TransactionTemplate(transactionManager);
    }
    static String text(String value,int maximum,boolean required) {
        if(value==null || value.length()>maximum || required&&value.isBlank() || value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')) throw new IllegalArgumentException("invalid clue text");
        return value.strip();
    }
    private void audit(String actor,String action,Object result) {db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,action,JSON.writeValueAsString(result),UUID.randomUUID());}
    private Map<String,Object> converted(Map<String,Object> row) {
        var copy=new LinkedHashMap<String,Object>(row);
        copy.remove("create_fingerprint");
        for(var entry:copy.entrySet()) if(entry.getValue() instanceof Timestamp time)entry.setValue(time.toInstant().toString());
        return copy;
    }
    private Map<String,Object> collection(UUID id,boolean lock) {
        var rows=db.queryForList("SELECT * FROM clue_collections WHERE collection_id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.size()!=1)throw new AdminService.Rejected(404,"collection_unavailable");
        // Fail closed if this workspace contains a camera that is outside the current server scope.
        var cameras=db.queryForList("SELECT DISTINCT o.camera_id FROM clue_collection_items i JOIN observations o USING(observation_id) WHERE i.collection_id=?",id);
        if(cameras.stream().anyMatch(row->!search.mayReadCamera((String)row.get("camera_id"))))throw new AdminService.Rejected(404,"collection_unavailable");
        return converted(rows.getFirst());
    }
    private Map<String,Object> writable(UUID id,long revision) {
        var row=collection(id,true);
        requireWritable(row,revision);
        return row;
    }
    private static void requireWritable(Map<String,Object> row,long revision) {
        if(revision<1 || ((Number)row.get("revision")).longValue()!=revision)throw new AdminService.Rejected(409,"collection_revision_conflict");
        if(!"open".equals(row.get("state")))throw new AdminService.Rejected(409,"collection_closed");
    }
    private Map<String,Object> observation(UUID id,long expectedVersion) {
        var rows=db.queryForList("SELECT observation_id,camera_id,record_version FROM observations WHERE observation_id=? AND NOT deleted FOR SHARE",id);
        if(rows.size()!=1 || !search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new AdminService.Rejected(404,"observation_unavailable");
        var row=rows.getFirst();
        if(expectedVersion>0 && ((Number)row.get("record_version")).longValue()!=expectedVersion)throw new AdminService.Rejected(409,"observation_revision_conflict");return row;
    }
    private void changed(UUID id,String actor,String action,Object target) {
        db.update("UPDATE clue_collections SET revision=revision+1,updated_at=clock_timestamp() WHERE collection_id=?",id);
        audit(actor,action,Map.of("collection_id",id,"target",target));
    }
    public Map<String,Object> list(String state,int limit,UUID after) {
        if(!Set.of("all","open","closed").contains(state) || limit<1 || limit>50)throw new IllegalArgumentException("invalid collection page");
        // Scan a bounded server page, then enforce scope before returning even titles/counts.
        var rows=db.queryForList("SELECT collection_id FROM clue_collections WHERE (?='all' OR state=?) AND (?::uuid IS NULL OR collection_id>?) ORDER BY collection_id LIMIT ?",state,state,after,after,limit+1);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows.subList(0,Math.min(limit,rows.size()))) {
            UUID id=(UUID)row.get("collection_id");
            try {var view=new LinkedHashMap<>(collection(id,false));view.put("item_count",db.queryForObject("SELECT count(*) FROM clue_collection_items WHERE collection_id=?",Long.class,id));items.add(view);}
            catch(AdminService.Rejected hidden) {if(hidden.status!=404)throw hidden;}
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("has_more",rows.size()>limit);result.put("next_cursor",rows.size()>limit?rows.get(limit-1).get("collection_id").toString():null);return result;
    }
    @Transactional
    public Map<String,Object> create(Create body,String actor) {
        if(body==null || body.requestId()==null)throw new IllegalArgumentException("missing request id");
        String title=text(body.title(),160,true),note=text(body.note(),2000,false);
        String fingerprint=SearchCursorCodec.fingerprint(Map.of("title",title,"note",note,"actor",actor));
        db.execute("SELECT pg_advisory_xact_lock(775533110)");
        var prior=db.queryForList("SELECT create_fingerprint FROM clue_collections WHERE collection_id=?",body.requestId());
        if(!prior.isEmpty()) {
            if(!fingerprint.equals(prior.getFirst().get("create_fingerprint")))throw new AdminService.Rejected(409,"collection_request_conflict");return read(body.requestId());
        }
        db.update("INSERT INTO clue_collections(collection_id,create_fingerprint,title,note,created_by) VALUES(?,?,?,?,?)",body.requestId(),fingerprint,title,note,actor);
        audit(actor,"clues.create",Map.of("collection_id",body.requestId()));return read(body.requestId());
    }
    @Transactional
    public Map<String,Object> edit(UUID id,Edit body,String actor) {
        if(body==null || body.state()==null || !Set.of("open","closed").contains(body.state()))throw new IllegalArgumentException("invalid collection state");
        String title=text(body.title(),160,true),note=text(body.note(),2000,false);var prior=collection(id,true);
        if(((Number)prior.get("revision")).longValue()!=body.expectedRevision())throw new AdminService.Rejected(409,"collection_revision_conflict");
        db.update("UPDATE clue_collections SET title=?,note=?,state=? WHERE collection_id=?",title,note,body.state(),id);
        changed(id,actor,"clues.edit",Map.of("before",prior,"after",Map.of("title",title,"note",note,"state",body.state())));return read(id);
    }
    @Transactional
    public Map<String,Object> add(UUID id,UUID observationId,Add body,String actor) {
        if(body==null || body.observationVersion()<1)throw new IllegalArgumentException("missing observation version");
        writable(id,body.expectedRevision());observation(observationId,body.observationVersion());
        if(db.queryForObject("SELECT count(*) FROM clue_collection_items WHERE collection_id=? AND observation_id=?",Long.class,id,observationId)>0)return read(id);
        if(db.queryForObject("SELECT count(*) FROM clue_collection_items WHERE collection_id=?",Long.class,id)>=200)throw new AdminService.Rejected(409,"collection_full");
        db.update("INSERT INTO clue_collection_items(collection_id,observation_id,added_record_version,added_by) VALUES(?,?,?,?)",id,observationId,body.observationVersion(),actor);
        changed(id,actor,"clues.add",Map.of("observation_id",observationId,"record_version",body.observationVersion()));return read(id);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> review(UUID id,UUID observationId,Review body,String actor) throws Exception {
        if(body==null || body.state()==null || !Set.of("pending","retained","excluded").contains(body.state()) || body.observationVersion()<1)throw new IllegalArgumentException("invalid review");
        String note=text(body.note(),2000,false);
        requireWritable(collection(id,false),body.expectedRevision());
        if(!"pending".equals(body.state())) {
            if(body.evidenceSha256()==null || !body.evidenceSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("review needs evidence digest");
            var detail=details.read(observationId).orElseThrow(()->new AdminService.Rejected(404,"observation_unavailable"));
            var image=(Map<String,Object>)detail.get("evidence");
            if(!Boolean.TRUE.equals(image.get("available")) || !body.evidenceSha256().equals(image.get("sha256")) || "mismatch".equals(image.get("capture_integrity")))throw new AdminService.Rejected(409,"review_evidence_changed");
        }
        // Verify the immutable JPEG before acquiring database locks, then
        // recheck both revisions under the short write transaction.
        return transactions.execute(status -> persistReview(id,observationId,body,note,actor));
    }
    private Map<String,Object> persistReview(UUID id,UUID observationId,Review body,String note,String actor) {
        writable(id,body.expectedRevision());observation(observationId,body.observationVersion());
        var previous=db.queryForList("SELECT review_state,review_note,reviewed_record_version,reviewed_evidence_sha256,reviewed_by,reviewed_at FROM clue_collection_items WHERE collection_id=? AND observation_id=? FOR UPDATE",id,observationId);
        if(previous.size()!=1)throw new AdminService.Rejected(404,"item_unavailable");
        db.update("UPDATE clue_collection_items SET review_state=?,review_note=?,reviewed_record_version=?,reviewed_evidence_sha256=?,reviewed_by=?,reviewed_at=clock_timestamp() WHERE collection_id=? AND observation_id=?",
            body.state(),note,body.observationVersion(),"pending".equals(body.state())?null:body.evidenceSha256(),actor,id,observationId);
        var after=new LinkedHashMap<String,Object>();after.put("state",body.state());after.put("note",note);after.put("record_version",body.observationVersion());after.put("evidence_sha256","pending".equals(body.state())?null:body.evidenceSha256());
        changed(id,actor,"clues.review",Map.of("observation_id",observationId,"before",converted(previous.getFirst()),"after",after));return read(id);
    }
    @Transactional
    public Map<String,Object> link(UUID id,UUID from,UUID to,Link body,String actor) {
        if(body==null || from.equals(to) || body.state()==null || !Set.of("pending","accepted","rejected").contains(body.state()))throw new IllegalArgumentException("invalid candidate link");
        String reason=text(body.reason(),1000,true);writable(id,body.expectedRevision());
        long count=db.queryForObject("SELECT count(*) FROM clue_collection_items WHERE collection_id=? AND observation_id IN (?,?)",Long.class,id,from,to);
        if(count!=2)throw new AdminService.Rejected(404,"item_unavailable");
        // Lock both observations in stable order, retaining basis versions without asserting identity.
        var ordered=new ArrayList<>(List.of(from,to));ordered.sort(Comparator.naturalOrder());var versions=new HashMap<UUID,Long>();
        for(UUID observationId:ordered)versions.put(observationId,((Number)observation(observationId,0).get("record_version")).longValue());
        if(db.queryForObject("SELECT count(*) FROM clue_candidate_links WHERE collection_id=?",Long.class,id)>=400
                && db.queryForObject("SELECT count(*) FROM clue_candidate_links WHERE collection_id=? AND from_observation_id=? AND to_observation_id=?",Long.class,id,from,to)==0)throw new AdminService.Rejected(409,"collection_full");
        var previous=db.queryForList("SELECT * FROM clue_candidate_links WHERE collection_id=? AND from_observation_id=? AND to_observation_id=?",id,from,to);
        db.update(""" 
            INSERT INTO clue_candidate_links(collection_id,from_observation_id,to_observation_id,from_record_version,to_record_version,reason,review_state,updated_by)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(collection_id,from_observation_id,to_observation_id) DO UPDATE
            SET from_record_version=excluded.from_record_version,to_record_version=excluded.to_record_version,reason=excluded.reason,
                review_state=excluded.review_state,updated_by=excluded.updated_by,updated_at=clock_timestamp()
            """,id,from,to,versions.get(from),versions.get(to),reason,body.state(),actor);
        changed(id,actor,"clues.link",Map.of("from",from,"to",to,"before",previous.stream().map(this::converted).toList(),
            "after",Map.of("state",body.state(),"reason",reason,"from_record_version",versions.get(from),"to_record_version",versions.get(to))));return read(id);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> read(UUID id) {
        var head=collection(id,false);
        var nodes=db.queryForList("""
            SELECT i.*,o.frame_id,o.camera_id,o.target_type,o.description,o.record_version,o.deleted,
              o.attributes::text AS attributes_text,o.uncertain_fields::text AS uncertain_text,
              c.display_name AS camera_name,f.source_time,f.source_time_origin,f.received_at,f.media_offset_ms,s.source_type
            FROM clue_collection_items i JOIN observations o USING(observation_id) JOIN frames f USING(frame_id)
            JOIN cameras c ON c.camera_id=o.camera_id JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s USING(source_id)
            WHERE i.collection_id=? ORDER BY i.added_at,i.observation_id
            """,id).stream().map(this::converted).toList();
        var versions=new HashMap<Object,Long>();
        for(var node:nodes) {
            if(!Boolean.TRUE.equals(node.get("deleted")))versions.put(node.get("observation_id"),((Number)node.get("record_version")).longValue());
            node.put("review_stale",node.get("reviewed_record_version")!=null && !Objects.equals(((Number)node.get("reviewed_record_version")).longValue(),((Number)node.get("record_version")).longValue()));
            node.put("available",!Boolean.TRUE.equals(node.remove("deleted")));
            node.put("attributes",ObservationStore.indexedAttributes(JSON.readValue((String)node.remove("attributes_text"),Map.class),JSON.readValue((String)node.remove("uncertain_text"),List.class)));
            if(!Boolean.TRUE.equals(node.get("available"))) {
                // Keep only the reference and review audit for withdrawn observations.
                for(String field:List.of("description","attributes","frame_id","source_time","received_at","media_offset_ms"))node.remove(field);
                node.put("review_stale",true);
            }
        }
        boolean chronological=verifiedTimeline(nodes);
        if(chronological)nodes=new ArrayList<>(nodes.stream().sorted(Comparator.comparing(node->Instant.parse((String)node.get("source_time")))).toList());
        var links=db.queryForList("SELECT * FROM clue_candidate_links WHERE collection_id=? ORDER BY updated_at,from_observation_id,to_observation_id",id).stream().map(this::converted).toList();
        for(var edge:links)edge.put("review_stale",!Objects.equals(versions.get(edge.get("from_observation_id")),((Number)edge.get("from_record_version")).longValue()) || !Objects.equals(versions.get(edge.get("to_observation_id")),((Number)edge.get("to_record_version")).longValue()));
        return Map.of("collection",head,"nodes",nodes,"links",links,"timeline_order",chronological?"verified_source_time":"collection_order","association_kind","candidate_only");
    }
    static boolean verifiedTimeline(List<Map<String,Object>> nodes) {
        return !nodes.isEmpty() && nodes.stream().allMatch(node->Boolean.TRUE.equals(node.get("available")) && "verified".equals(node.get("source_time_origin")) && node.get("source_time") instanceof String);
    }
    static String csvCell(Object raw) {
        String value=raw==null?"":raw.toString();String trimmed=value.stripLeading();
        if(!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0))>=0 || value.startsWith("\t") || value.startsWith("\r"))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    @SuppressWarnings("unchecked")
    public Export export(UUID id,long revision,String format,String actor) throws Exception {
        if(!Set.of("json","csv").contains(format))throw new IllegalArgumentException("invalid export format");
        var view=read(id);var head=(Map<String,Object>)view.get("collection");
        if(((Number)head.get("revision")).longValue()!=revision)throw new AdminService.Rejected(409,"collection_revision_conflict");
        var manifest=new LinkedHashMap<String,Object>(view);manifest.put("schema_version","clue-collection-v1");manifest.put("exported_at",Instant.now().toString());manifest.put("exported_by",actor);
        String content;
        if(format.equals("json"))content=JSON.writeValueAsString(manifest);
        else {
            var lines=new ArrayList<String>();
            var columns=List.of("observation_id","camera_id","camera_name","source_time","received_at","target_type","description","record_version","review_state","review_note","reviewed_by","reviewed_at","reviewed_record_version","reviewed_evidence_sha256","review_stale","available");
            lines.add(String.join(",",columns));
            for(var node:(List<Map<String,Object>>)view.get("nodes"))lines.add(String.join(",",columns.stream().map(key->csvCell(node.get(key))).toList()));
            content="\ufeff"+String.join("\r\n",lines)+"\r\n";
        }
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>2*1024*1024)throw new AdminService.Rejected(409,"collection_export_too_large");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        audit(actor,"clues.export",Map.of("collection_id",id,"revision",revision,"format",format,"sha256",sha,"bytes",bytes.length));
        return new Export(bytes,sha,format.equals("csv")?"text/csv; charset=utf-8":"application/json", "clues-"+id+"-r"+revision+"."+format);
    }
}
