package cn.trial.videosearch.exports;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.*;

/** Resolves only assets already bound to the selected frames, never camera-only matches. */
@Service
public class ExportAssets {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    public record Content(Path path,byte[] bytes,long size,String sha256,String mediaType){}
    public record Media(Map<String,Object> asset,Map<String,Object> source,Map<String,Object> binding){}
    private final JdbcTemplate db;private final EvidenceFiles images;
    private final MediaClips clips;
    public ExportAssets(JdbcTemplate db,EvidenceFiles images,MediaClips clips){this.db=db;this.images=images;this.clips=clips;}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw new InputRejected(409,"export_source_changed");return (Map<String,Object>)value;}
    public Map<UUID,UUID> normalizedCandidates(List<UUID> frames){
        if(frames.isEmpty())return Map.of();String slots=String.join(",",Collections.nCopies(frames.size(),"?"));
        var result=new HashMap<UUID,UUID>();
        for(var row:db.queryForList("""
            SELECT frame_id,clip_id FROM (
              SELECT f.frame_id,r.clip_id,row_number() OVER(PARTITION BY f.frame_id ORDER BY r.created_at DESC,r.clip_id) AS rank
              FROM frames f JOIN processing_tasks t USING(task_id) JOIN ingestion_task_specs p USING(task_id)
              JOIN recorded_media_clips r ON r.source_id=t.source_id AND r.manifest->>'schema_version'='recorded-media-clip-v1'
                AND r.manifest->'source'->>'sha256'=p.source_snapshot->'file'->>'sha256'
                AND r.manifest->'source'->>'bytes'=p.source_snapshot->'file'->>'bytes'
                AND f.media_offset_ms*1000 BETWEEN (r.manifest->>'actual_start_us')::bigint AND (r.manifest->>'actual_end_us')::bigint
              WHERE f.frame_id IN (%s)) candidates WHERE rank=1
            """.formatted(slots),frames.toArray()))result.put((UUID)row.get("frame_id"),(UUID)row.get("clip_id"));
        return result;
    }
    public Media normalizedMedia(UUID frame,UUID clip){
        var value=clips.verify(clip,MediaClipFiles.Mode.VIDEO,-1);verifyNormalizedBinding(frame,clip,value.manifest());
        var video=object(value.manifest().get("video"));String id=clip.toString(),key="normalized-"+id;
        long offset=db.queryForObject("SELECT media_offset_ms FROM frames WHERE frame_id=?",Long.class,frame)-((Number)value.manifest().get("actual_start_us")).longValue()/1000;
        return new Media(asset(key,"normalized",id,frame.toString(),id,value.videoBytes(),(String)video.get("sha256"),value.sha256(),"media/normalized/"+id+".mp4"),source(key,value.manifest(),value.sha256()),Map.of("frame_id",frame.toString(),"kind","normalized","asset_key",key,"seek_offset_ms",Math.max(0,offset),"seek_kind","approximate_normalized_offset"));
    }
    private static Map<String,Object> source(String key,Map<String,Object> manifest,String sha){var encoded=CanonicalJson.encode(manifest,256*1024);if(!sha.equals(encoded.sha256()))throw new InputRejected(409,"export_source_changed");return Map.of("asset_key",key,"filename","sources/"+key+".json","manifest_payload",encoded.payload(),"sha256",sha,"sample_files_included",false);}
    static Map<String,Object> asset(String key,String kind,String reference,String frame,String identity,long bytes,String sha,String manifest,String filename){
        var value=new LinkedHashMap<String,Object>();value.put("asset_key",key);value.put("kind",kind);value.put("reference_id",reference);value.put("frame_id",frame);value.put("identity",identity);value.put("bytes",bytes);value.put("sha256",sha);value.put("manifest_sha256",manifest);value.put("filename",filename);return value;
    }
    private void verifyNormalizedBinding(UUID frame,UUID clip,Map<String,Object> manifest){
        var source=object(manifest.get("source"));
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM frames f JOIN processing_tasks t USING(task_id) JOIN ingestion_task_specs p USING(task_id) WHERE f.frame_id=? AND t.source_id=? AND p.source_snapshot->'file'->>'sha256'=? AND p.source_snapshot->'file'->>'bytes'=? AND f.media_offset_ms*1000 BETWEEN ? AND ?)",Boolean.class,frame,UUID.fromString((String)manifest.get("source_id")),source.get("sha256"),source.get("bytes").toString(),manifest.get("actual_start_us"),manifest.get("actual_end_us"))))throw new InputRejected(409,"export_source_changed");
    }
    public void verifyBinding(Map<String,Object> asset,UUID frame){
        if("normalized".equals(asset.get("kind"))){UUID id=UUID.fromString((String)asset.get("reference_id"));if(!Objects.equals(clips.read(id).get("manifest_sha256"),asset.get("manifest_sha256")))throw new InputRejected(409,"export_source_changed");var manifest=JSON.readValue(db.queryForObject("SELECT manifest::text FROM recorded_media_clips WHERE clip_id=?",String.class,id),Map.class);verifyNormalizedBinding(frame,id,object(manifest));}
    }
    private Map<String,Object> databaseFacts(Map<String,Object> asset,UUID frame){
        if(!"normalized".equals(asset.get("kind")))throw new InputRejected(409,"export_source_changed");
        var rows=db.queryForList("""
            SELECT jsonb_build_object('frame',to_jsonb(f),'clip',to_jsonb(r),
              'archive',p.source_snapshot,
              'identity',r.clip_id,'manifest_sha256',r.manifest_sha256,
              'bound',coalesce(r.source_id=t.source_id
                AND r.manifest->'source'->>'sha256'=p.source_snapshot->'file'->>'sha256'
                AND r.manifest->'source'->>'bytes'=p.source_snapshot->'file'->>'bytes'
                AND f.media_offset_ms*1000 BETWEEN (r.manifest->>'actual_start_us')::bigint AND (r.manifest->>'actual_end_us')::bigint,false))::text AS facts
            FROM frames f LEFT JOIN recorded_media_clips r ON r.clip_id=?
            LEFT JOIN processing_tasks t ON t.task_id=f.task_id LEFT JOIN ingestion_task_specs p ON p.task_id=f.task_id
            WHERE f.frame_id=?
            """,UUID.fromString((String)asset.get("identity")),frame);
        if(rows.size()!=1)throw new InputRejected(409,"export_source_changed");var facts=ExportPlans.decode((String)rows.getFirst().get("facts"));
        if(!Boolean.TRUE.equals(facts.get("bound"))||!asset.get("identity").equals(facts.get("identity"))||!asset.get("manifest_sha256").equals(facts.get("manifest_sha256")))throw new InputRejected(409,"export_source_changed");
        return facts;
    }
    public String databaseSignature(Map<String,Object> asset,UUID frame){return CanonicalJson.encode(databaseFacts(asset,frame),1024*1024).sha256();}
    public void verifyDatabaseBinding(Map<String,Object> asset,Map<String,Object> binding){
        String signature=databaseSignature(asset,UUID.fromString((String)binding.get("frame_id")));
        if(binding.containsKey("database_sha256")&&!signature.equals(binding.get("database_sha256")))throw new InputRejected(409,"export_source_changed");
    }
    public Content read(Map<String,Object> asset)throws Exception{
        UUID frame=UUID.fromString((String)asset.get("frame_id"));String kind=(String)asset.get("kind");Content value;
        if("image".equals(kind)){
            var image=images.read(frame,"evidence:"+frame.toString().replace("-",""));if(image==null)throw new InputRejected(409,"export_asset_unavailable");
            value=new Content(null,image.bytes(),image.bytes().length,image.sha256(),"image/jpeg");
        }else if("normalized".equals(kind)){
            UUID clip=UUID.fromString((String)asset.get("reference_id"));var media=clips.verify(clip,MediaClipFiles.Mode.VIDEO,-1);verifyNormalizedBinding(frame,clip,media.manifest());
            if(!media.sha256().equals(asset.get("manifest_sha256")))throw new InputRejected(409,"export_source_changed");
            value=new Content(media.video(),null,media.videoBytes(),(String)object(media.manifest().get("video")).get("sha256"),"video/mp4");
        }else throw new IllegalArgumentException("Unknown export asset");
        if(value.size()!=((Number)asset.get("bytes")).longValue()||!value.sha256().equals(asset.get("sha256")))throw new InputRejected(409,"export_source_changed");return value;
    }
}
