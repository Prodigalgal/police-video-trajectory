package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;

@Service
public class MediaClips {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    private final JdbcTemplate db;
    private final SearchOrchestrator search;
    private final MediaClipFiles files;
    private final TransactionTemplate transaction;
    public MediaClips(JdbcTemplate db,SearchOrchestrator search,MediaClipFiles files,PlatformTransactionManager manager){this.db=db;this.search=search;this.files=files;transaction=new TransactionTemplate(manager);}
    public record Register(UUID clipId,String manifestSha256,String workerId){}
    public Map<String,Object> register(Register request){
        if(request==null||request.clipId()==null||request.manifestSha256()==null||!request.manifestSha256().matches("[a-f0-9]{64}")||request.workerId()==null||!request.workerId().matches("[A-Za-z0-9_.-]{1,70}"))throw new IllegalArgumentException("Invalid media registration");
        var previous=db.queryForList("SELECT manifest_sha256 FROM recorded_media_clips WHERE clip_id=?",request.clipId());
        if(!previous.isEmpty()){
            if(!request.manifestSha256().equals(previous.getFirst().get("manifest_sha256")))throw new InputRejected(409,"media_registration_conflict");
            // The retained derivative is self-contained after registration. A
            // lost acknowledgement must not require re-reading the old source.
            verify(request.clipId(),MediaClipFiles.Mode.MANIFEST,-1,false);return summary(stored(request.clipId(),false));
        }
        var assets=files.verify(request.clipId(),request.manifestSha256(),MediaClipFiles.Mode.MANIFEST,-1);
        String fingerprint=source(assets.sourceId(),assets.camera());
        files.verifyRecording(assets);
        transaction.executeWithoutResult(status->{
            db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","media-clip:"+request.clipId());
            db.queryForList("SELECT source_id FROM sources WHERE source_id=? FOR SHARE",assets.sourceId());
            if(!fingerprint.equals(source(assets.sourceId(),assets.camera())))throw new InputRejected(409,"media_source_changed");
            var rows=db.queryForList("SELECT manifest_sha256,source_fingerprint FROM recorded_media_clips WHERE clip_id=?",request.clipId());
            if(!rows.isEmpty()){
                if(!request.manifestSha256().equals(rows.getFirst().get("manifest_sha256"))||!fingerprint.equals(rows.getFirst().get("source_fingerprint")))throw new InputRejected(409,"media_registration_conflict");
                return;
            }
            db.update("INSERT INTO recorded_media_clips(clip_id,source_id,camera_id,source_fingerprint,manifest,manifest_sha256,registered_by) VALUES(?,?,?,?,?::jsonb,?,?)",request.clipId(),assets.sourceId(),assets.camera(),fingerprint,assets.payload(),assets.sha256(),request.workerId());
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",request.workerId(),"register_media_clip","registered",request.clipId());
        });
        return summary(stored(request.clipId(),false));
    }
    String source(UUID id,String camera){
        var rows=db.queryForList("SELECT source_id,camera_id,source_type,source_ref,source_time_origin FROM sources WHERE source_id=?",id);
        if(rows.size()!=1||!camera.equals(rows.getFirst().get("camera_id"))||!search.mayReadCamera(camera)||!"archive".equals(rows.getFirst().get("source_type"))||!("archive:"+camera).equals(rows.getFirst().get("source_ref")))throw new InputRejected(404,"media_source_unavailable");
        return MediaSourceIdentity.fingerprint(rows.getFirst());
    }
    private Map<String,Object> stored(UUID id,boolean publicView){
        var rows=db.queryForList("SELECT clip_id,source_id,camera_id,source_fingerprint,manifest::text AS manifest_text,manifest_sha256,registered_by,created_at FROM recorded_media_clips WHERE clip_id=?",id);
        if(rows.size()!=1||!search.mayReadCamera((String)rows.getFirst().get("camera_id")))throw new InputRejected(404,"media_unavailable");
        var row=rows.getFirst();Map<String,Object> saved;
        try{saved=JSON.readValue((String)row.get("manifest_text"),Map.class);if(!CanonicalJson.encode(saved,64*1024).sha256().equals(row.get("manifest_sha256")))throw new IllegalArgumentException();}
        catch(RuntimeException malformed){throw new InputRejected(409,"media_manifest_changed");}
        if(!source((UUID)row.get("source_id"),(String)row.get("camera_id")).equals(row.get("source_fingerprint")))throw new InputRejected(409,"media_source_changed");
        return row;
    }
    public Map<String,Object> read(UUID id){return summary(stored(id,true));}
    public MediaClipFiles.Assets verify(UUID id,MediaClipFiles.Mode mode,int index){
        return verify(id,mode,index,true);
    }
    public MediaClipFiles.Assets verify(UUID id,MediaClipFiles.Mode mode,int index,boolean publicView){
        var row=stored(id,publicView);var verified=files.verify(id,(String)row.get("manifest_sha256"),mode,index);
        if(!verified.sourceId().equals(row.get("source_id"))||!verified.camera().equals(row.get("camera_id")))throw new InputRejected(409,"media_manifest_changed");
        stored(id,publicView);return verified;
    }
    public Map<String,Object> list(String camera,int limit,UUID after){
        if(!search.mayReadCamera(camera))throw new InputRejected(404,"media_unavailable");if(limit<1||limit>50)throw new IllegalArgumentException("Invalid media page");
        var rows=db.queryForList("SELECT clip_id,source_id,camera_id,manifest_sha256,registered_by,created_at FROM recorded_media_clips WHERE camera_id=? AND (?::uuid IS NULL OR clip_id>?) ORDER BY clip_id LIMIT ?",camera,after,after,limit+1);
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));
        var value=new LinkedHashMap<String,Object>();value.put("items",page.stream().map(MediaClips::summary).toList());value.put("has_more",more);value.put("next_cursor",more?page.getLast().get("clip_id").toString():null);return value;
    }
    private static Map<String,Object> summary(Map<String,Object> row){
        var value=new LinkedHashMap<String,Object>();for(String key:List.of("clip_id","source_id","camera_id","manifest_sha256","registered_by"))value.put(key,row.get(key) instanceof UUID id?id.toString():row.get(key));
        value.put("created_at",((Timestamp)row.get("created_at")).toInstant().toString());value.put("inference_invoked",false);value.put("integrity_status","not_revalidated");return value;
    }
}
