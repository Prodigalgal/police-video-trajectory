package cn.trial.videosearch.evidence;

import java.util.*;

/** One spatial rule shared by read projections and authoritative confirmation. */
public record PlateReviewTarget(UUID eventId,List<Long> box,List<Integer> eligible) {
    private static final double MIN_IOU=.5;
    public static Optional<PlateReviewTarget> requested(Map<String,Object> decision,Map<String,Object> job,Map<String,Object> result){
        try{
            if(!"requested".equals(decision.get("selection_mode"))||!job.get("frame_id").toString().equals(decision.get("frame_id"))||!Objects.equals(result.get("evidence_sha256"),decision.get("jpeg_sha256")))throw new IllegalArgumentException();
            var vehicle=SpecialistAdmission.requestedBox(decision.get("vehicle_bbox_xyxy"),((Number)result.get("image_width")).longValue(),((Number)result.get("image_height")).longValue());
            if(vehicle.size()!=4)return Optional.empty();var candidates=new ArrayList<PlateReviewTarget>();
            if(!(result.get("regions") instanceof List<?> regions))throw new IllegalArgumentException();
            for(int index=0;index<regions.size();index++){
                var region=SpecialistResult.object(regions.get(index));var box=SpecialistAdmission.requestedBox(region.get("bbox_xyxy"),((Number)result.get("image_width")).longValue(),((Number)result.get("image_height")).longValue());
                if(box.size()!=4)throw new IllegalArgumentException();
                double area=(double)(box.get(2)-box.get(0))*(box.get(3)-box.get(1));
                double intersection=(double)Math.max(0,Math.min(box.get(2),vehicle.get(2))-Math.max(box.get(0),vehicle.get(0)))*Math.max(0,Math.min(box.get(3),vehicle.get(3))-Math.max(box.get(1),vehicle.get(1)));
                if(intersection/area>=.9)candidates.add(new PlateReviewTarget(null,box,List.of(index)));
            }
            // Multiple plausible plates in one vehicle region require another image, never a guess.
            return candidates.size()==1?Optional.of(candidates.getFirst()):Optional.empty();
        }catch(RuntimeException invalid){throw new InputRejected(409,"plate_review_unavailable");}
    }
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
