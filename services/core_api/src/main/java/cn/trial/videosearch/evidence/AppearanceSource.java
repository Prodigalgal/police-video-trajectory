package cn.trial.videosearch.evidence;

import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Target geometry must be the same bound detector geometry used by evidence review. */
@Service
public class AppearanceSource {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final TemporalEvidence evidence;private final EvidenceFiles files;
    public AppearanceSource(TemporalEvidence evidence,EvidenceFiles files){this.evidence=evidence;this.files=files;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> current(UUID observation,long revision){
        var anchor=evidence.anchor(observation,revision);var row=anchor.observation();var frame=anchor.frame();
        if(row.get("target_id")==null||!AppearanceCatalog.hex(row.get("payload_sha256"))||!Objects.equals(row.get("target_type"),row.get("detector_object_type")))throw new InputRejected(409,"appearance_geometry_unavailable");
        try{
            var box=JSON.readValue((String)row.get("detector_bbox"),List.class);
            var observed=JSON.readValue((String)row.get("observation_bbox"),List.class);
            var view=frame.view();int width=(int)AppearanceCatalog.integer(view.get("image_width"),1,20000),height=(int)AppearanceCatalog.integer(view.get("image_height"),1,20000);
            validateBox(box,width,height);if(box.size()!=observed.size())throw new IllegalArgumentException();
            for(int i=0;i<4;i++)if(!(observed.get(i) instanceof Number b)||!Double.isFinite(b.doubleValue())||Math.abs(((Number)box.get(i)).doubleValue()-b.doubleValue())>0.000001)throw new IllegalArgumentException();
            var descriptor=new LinkedHashMap<String,Object>();descriptor.put("observation_id",observation.toString());descriptor.put("record_version",revision);
            descriptor.put("frame_id",frame.id().toString());descriptor.put("camera_id",frame.camera());descriptor.put("target_type",row.get("target_type"));descriptor.put("target_id",row.get("target_id").toString());
            descriptor.put("bbox_xyxy",box.stream().map(x->((Number)x).doubleValue()).toList());descriptor.put("image_width",width);descriptor.put("image_height",height);descriptor.put("image_sha256",row.get("payload_sha256"));
            descriptor.put("frame_fingerprint",frame.fingerprint());descriptor.put("anchor_fingerprint",anchor.fingerprint());
            return descriptor;
        }catch(IllegalArgumentException malformed){throw new InputRejected(409,"appearance_geometry_unavailable");}
    }
    static void validateBox(List<?> box,int width,int height){
        if(box==null||box.size()!=4)throw new IllegalArgumentException();double[] b=new double[4];
        for(int i=0;i<4;i++){if(!(box.get(i) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException();b[i]=n.doubleValue();}
        if(b[0]<0||b[1]<0||b[2]>width||b[3]>height||b[2]-b[0]<12||b[3]-b[1]<12||(long)width*height>16_777_216)throw new IllegalArgumentException();
    }
    public void verify(Map<String,Object> descriptor){
        var actual=current(UUID.fromString((String)descriptor.get("observation_id")),((Number)descriptor.get("record_version")).longValue());
        if(!CanonicalJson.encode(actual,16384).sha256().equals(CanonicalJson.encode(descriptor,16384).sha256()))throw new InputRejected(409,"appearance_source_changed");
    }
    public EvidenceFiles.FrameFile image(Map<String,Object> descriptor){
        verify(descriptor);UUID frame=UUID.fromString((String)descriptor.get("frame_id"));
        try{
            var image=files.read(frame,"evidence:"+frame.toString().replace("-",""));
            if(image==null||!image.sha256().equals(descriptor.get("image_sha256"))||image.width()!=((Number)descriptor.get("image_width")).intValue()||image.height()!=((Number)descriptor.get("image_height")).intValue())throw new InputRejected(409,"appearance_source_changed");
            verify(descriptor);return image;
        }catch(java.io.IOException unavailable){throw new InputRejected(503,"appearance_image_unavailable");}
    }
}
