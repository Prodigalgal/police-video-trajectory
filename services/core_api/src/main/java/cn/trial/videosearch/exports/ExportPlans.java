package cn.trial.videosearch.exports;

import cn.trial.videosearch.clues.ClueCollections;
import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.investigation.EvidencePackageManifest;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ExportPlans {
    public static final int MAX_MANIFEST=4*1024*1024;
    public static final long MAX_ASSETS=512L*1024*1024,MAX_ARTIFACT=516L*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final EvidencePackageManifest images;private final ClueCollections clues;private final ExportAssets media;
    public ExportPlans(EvidencePackageManifest images,ClueCollections clues,ExportAssets media){this.images=images;this.clues=clues;this.media=media;}
    @SuppressWarnings("unchecked") public static Map<String,Object> decode(String raw){return JSON.readValue(raw,Map.class);}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> rows(Object raw){return (List<Map<String,Object>>)raw;}
    private static CanonicalJson.Encoded encoded(Object value,int bound){return CanonicalJson.encode(JSON.readValue(JSON.writeValueAsString(value),Map.class),bound);}
    public Map<String,Object> info(UUID id,long revision){
        var current=clues.read(id);if(((Number)((Map<?,?>)current.get("collection")).get("revision")).longValue()!=revision)throw new InputRejected(409,"collection_revision_conflict");
        long frames=rows(current.get("nodes")).stream().filter(node->Boolean.TRUE.equals(node.get("available"))&&node.get("frame_id")!=null).map(node->node.get("frame_id")).distinct().count();
        return Map.of("collection_id",id.toString(),"collection_revision",revision,"parts",Math.max(1,(frames+1)/2),"frame_count",frames,"frames_per_part",2,"max_artifact_bytes",MAX_ARTIFACT);
    }
    public CanonicalJson.Encoded prepare(UUID id,long revision,int part,boolean normalized,String actor)throws Exception{
        if(!normalized)throw new IllegalArgumentException("Select a video attachment kind");
        var base=decode((String)images.prepareWindow(id,revision,part,actor,2).get("manifest_payload"));
        var selected=rows(base.get("images"));var assets=new LinkedHashMap<String,Map<String,Object>>();var sources=new LinkedHashMap<String,Map<String,Object>>();
        if(selected.isEmpty())throw new InputRejected(422,"export_empty");
        var bindings=new ArrayList<Map<String,Object>>();var missing=new ArrayList<Map<String,Object>>();
        var frames=selected.stream().map(image->UUID.fromString((String)image.get("frame_id"))).toList();
        var clips=normalized?media.normalizedCandidates(frames):Map.<UUID,UUID>of();
        for(var image:selected){
            String frame=(String)image.get("frame_id"),key="image-"+frame;
            assets.put(key,ExportAssets.asset(key,"image",frame,frame,frame,((Number)image.get("bytes")).longValue(),(String)image.get("sha256"),null,(String)image.get("filename")));
            if(normalized){var clip=clips.get(UUID.fromString(frame));if(clip!=null)add(media.normalizedMedia(UUID.fromString(frame),clip),assets,sources,bindings);else missing.add(Map.of("frame_id",frame,"kind","normalized","reason","no_verified_normalized_clip"));}
        }
        long total=assets.values().stream().mapToLong(asset->((Number)asset.get("bytes")).longValue()).sum();if(total>MAX_ASSETS)throw new InputRejected(409,"export_size_limit");
        long videos=assets.values().stream().filter(asset->!"image".equals(asset.get("kind"))).count();base.put("video_bytes_included",videos>0);
        var result=new LinkedHashMap<String,Object>();result.put("schema_version","evidence-export-v1");result.put("collection_id",id.toString());result.put("collection_revision",revision);result.put("part",part);result.put("parts",base.get("parts"));result.put("metadata",base);result.put("source_collection_sha256",encoded(base.get("collection"),2*1024*1024).sha256());result.put("assets",new ArrayList<>(assets.values()));result.put("media_sources",new ArrayList<>(sources.values()));result.put("media_bindings",bindings);result.put("missing_attachments",missing);result.put("asset_bytes",total);result.put("full_archive_included",false);result.put("derived_sample_files_included",false);
        result.put("video_count",videos);assertCurrent(result);return encoded(result,MAX_MANIFEST);
    }
    private void add(ExportAssets.Media value,Map<String,Map<String,Object>> assets,Map<String,Map<String,Object>> sources,List<Map<String,Object>> bindings){
        String key=(String)value.asset().get("asset_key");assets.putIfAbsent(key,value.asset());sources.putIfAbsent(key,value.source());
        var binding=new LinkedHashMap<String,Object>(value.binding());binding.put("database_sha256",media.databaseSignature(value.asset(),UUID.fromString((String)binding.get("frame_id"))));bindings.add(binding);
    }
    public void assertDatabaseCurrent(Map<String,Object> plan){
        var current=clues.read(UUID.fromString((String)plan.get("collection_id")));
        if(!encoded(current,2*1024*1024).sha256().equals(plan.get("source_collection_sha256")))throw new InputRejected(409,"export_source_changed");
        var byKey=new HashMap<String,Map<String,Object>>();for(var asset:rows(plan.get("assets")))byKey.put((String)asset.get("asset_key"),asset);
        for(var binding:rows(plan.get("media_bindings")))media.verifyDatabaseBinding(byKey.get((String)binding.get("asset_key")),binding);
    }
    public void assertCurrent(Map<String,Object> plan){
        var current=clues.read(UUID.fromString((String)plan.get("collection_id")));
        if(!encoded(current,2*1024*1024).sha256().equals(plan.get("source_collection_sha256")))throw new InputRejected(409,"export_source_changed");
        var byKey=new HashMap<String,Map<String,Object>>();for(var asset:rows(plan.get("assets")))byKey.put((String)asset.get("asset_key"),asset);
        for(var binding:rows(plan.get("media_bindings")))media.verifyBinding(byKey.get((String)binding.get("asset_key")),UUID.fromString((String)binding.get("frame_id")));
    }
}
