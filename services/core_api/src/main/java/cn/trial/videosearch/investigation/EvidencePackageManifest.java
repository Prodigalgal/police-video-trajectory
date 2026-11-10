package cn.trial.videosearch.investigation;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.clues.ClueCollections;
import cn.trial.videosearch.evidence.ObservationDetails;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Sources are inspected outside write transactions; the version is rechecked before returning. */
@Service
public class EvidencePackageManifest {
    private final ClueCollections clues;
    private final ObservationDetails details;
    private final JdbcTemplate db;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int PART_FRAMES=16;
    public EvidencePackageManifest(ClueCollections clues,ObservationDetails details,JdbcTemplate db){this.clues=clues;this.details=details;this.db=db;}
    @SuppressWarnings("unchecked") public Map<String,Object> prepare(UUID id,long revision,int part,String actor)throws Exception{
        return prepareWindow(id,revision,part,actor,PART_FRAMES);
    }
    @SuppressWarnings("unchecked") public Map<String,Object> prepareWindow(UUID id,long revision,int part,String actor,int partFrames)throws Exception{
        if(revision<1||partFrames<1||partFrames>PART_FRAMES||part<1||part>(200+partFrames-1)/partFrames)throw new IllegalArgumentException("Invalid package part");
        var collection=clues.read(id);var head=(Map<String,Object>)collection.get("collection");
        if(((Number)head.get("revision")).longValue()!=revision)throw new AdminService.Rejected(409,"collection_revision_conflict");
        String sourceSha=collectionSha(collection);
        var frames=new LinkedHashMap<String,Map<String,Object>>();
        for(var node:(List<Map<String,Object>>)collection.get("nodes"))if(Boolean.TRUE.equals(node.get("available"))&&node.get("frame_id")!=null)frames.putIfAbsent(node.get("frame_id").toString(),node);
        int parts=Math.max(1,(frames.size()+partFrames-1)/partFrames);
        if(part>parts)throw new IllegalArgumentException("Package part is outside collection");
        var selected=new ArrayList<>(frames.values());var images=new ArrayList<Map<String,Object>>();var observations=new ArrayList<Map<String,Object>>();
        int start=(part-1)*partFrames,end=Math.min(part*partFrames,selected.size());
        for(var node:selected.subList(Math.min(start,selected.size()),end)){
            var detail=details.read(UUID.fromString(node.get("observation_id").toString())).orElseThrow(()->new AdminService.Rejected(409,"package_source_changed"));
            if(!Objects.equals(node.get("record_version"),detail.get("record_version")))throw new AdminService.Rejected(409,"package_source_changed");
            var evidence=(Map<String,Object>)detail.get("evidence");
            if(!Boolean.TRUE.equals(evidence.get("available"))||"mismatch".equals(evidence.get("capture_integrity")))throw new AdminService.Rejected(409,"package_evidence_unavailable");
            images.add(Map.of("frame_id",node.get("frame_id"),"sha256",evidence.get("sha256"),"bytes",evidence.get("bytes"),"filename","frames/"+node.get("frame_id")+".jpg"));observations.add(detail);
        }
        var current=clues.read(id);if(((Number)((Map<?,?>)current.get("collection")).get("revision")).longValue()!=revision)throw new AdminService.Rejected(409,"collection_revision_conflict");
        if(!sourceSha.equals(collectionSha(current)))throw new AdminService.Rejected(409,"package_source_changed");
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schema_version","evidence-package-v1");manifest.put("collection",collection);manifest.put("part",part);manifest.put("parts",parts);manifest.put("frame_count",frames.size());manifest.put("images",images);manifest.put("observations",observations);manifest.put("prepared_by",actor);manifest.put("prepared_at",Instant.now().toString());manifest.put("video_bytes_included",false);
        var encoded=CanonicalJson.encode(JSON.readValue(JSON.writeValueAsString(manifest),Map.class),2*1024*1024);
        db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'prepare_evidence_package',?,?)",actor,CanonicalJson.encode(Map.of("collection_id",id.toString(),"revision",revision,"part",part,"manifest_sha256",encoded.sha256()),8192).payload(),UUID.randomUUID());
        return Map.of("manifest_payload",encoded.payload(),"manifest_sha256",encoded.sha256(),"part",part,"parts",parts);
    }
    private static String collectionSha(Map<String,Object> collection){
        return CanonicalJson.encode(JSON.readValue(JSON.writeValueAsString(collection),Map.class),2*1024*1024).sha256();
    }
}
