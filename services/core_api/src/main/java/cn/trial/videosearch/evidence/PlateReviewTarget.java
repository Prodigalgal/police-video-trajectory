package cn.trial.videosearch.evidence;

import java.util.*;

/** One spatial rule shared by read projections and authoritative confirmation. */
public record PlateReviewTarget(UUID eventId,List<Long> box,List<Integer> eligible) {
    private static final double MIN_IOU=.5;
    public static PlateReviewTarget bind(Map<String,Object> decision,Map<String,Object> job,Map<String,Object> result){
        try{
            UUID event=UUID.fromString(SpecialistResult.text(decision.get("event_id"),36));
            if(!job.get("frame_id").toString().equals(decision.get("frame_id"))||!Objects.equals(result.get("evidence_sha256"),decision.get("jpeg_sha256")))throw new IllegalArgumentException();
            long width=SpecialistResult.integer(result.get("image_width"),1,20000),height=SpecialistResult.integer(result.get("image_height"),1,20000);
            if(!(decision.get("plate_bbox_xyxy") instanceof List<?> raw)||raw.size()!=4)throw new IllegalArgumentException();
            long x=SpecialistResult.integer(raw.get(0),0,width-1),y=SpecialistResult.integer(raw.get(1),0,height-1);
            var box=List.of(x,y,SpecialistResult.integer(raw.get(2),x+1,width),SpecialistResult.integer(raw.get(3),y+1,height));
            if(!(result.get("regions") instanceof List<?> regions))throw new IllegalArgumentException();var eligible=new ArrayList<Integer>();
            for(int index=0;index<regions.size();index++){
                var region=SpecialistResult.object(regions.get(index));
                if(!(region.get("bbox_xyxy") instanceof List<?> bounds))throw new IllegalArgumentException();
                if(iou(box,bounds)>=MIN_IOU)eligible.add(index);
            }
            return new PlateReviewTarget(event,box,List.copyOf(eligible));
        }catch(RuntimeException invalid){throw new InputRejected(409,"plate_review_unavailable");}
    }
    public Map<String,Object> view(Map<String,Object> job,Map<String,Object> result){
        return Map.of("schema_version","plate-review-target-v1","job_id",job.get("job_id").toString(),"result_sha256",job.get("result_sha256"),
            "frame_id",job.get("frame_id").toString(),"evidence_sha256",result.get("evidence_sha256"),"plate_bbox_xyxy",box,"eligible_region_indices",eligible);
    }
    public static double iou(List<?> a,List<?> b){
        double ax=((Number)a.get(0)).doubleValue(),ay=((Number)a.get(1)).doubleValue(),ar=((Number)a.get(2)).doubleValue(),ab=((Number)a.get(3)).doubleValue();
        double bx=((Number)b.get(0)).doubleValue(),by=((Number)b.get(1)).doubleValue(),br=((Number)b.get(2)).doubleValue(),bb=((Number)b.get(3)).doubleValue();
        double intersection=Math.max(0,Math.min(ar,br)-Math.max(ax,bx))*Math.max(0,Math.min(ab,bb)-Math.max(ay,by));
        double union=(ar-ax)*(ab-ay)+(br-bx)*(bb-by)-intersection;return union>0?intersection/union:0;
    }
}
