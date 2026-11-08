package cn.trial.videosearch.evidence;

import java.util.*;

public record TemporalWindow(int beforeMs,int afterMs,int maxFrames,int maxGapMs) {
    public TemporalWindow {
        if(beforeMs<0||beforeMs>120000||afterMs<0||afterMs>120000||maxFrames<1||maxFrames>24||maxGapMs<0||maxGapMs>10000
                ||maxGapMs==0&&(beforeMs!=0||afterMs!=0||maxFrames!=1))throw new IllegalArgumentException("Invalid context window");
    }
    public static TemporalWindow defaults(){return new TemporalWindow(60000,60000,16,6000);}
    public static TemporalWindow single(){return new TemporalWindow(0,0,1,0);}
    public Map<String,Object> value(){return Map.of("before_ms",beforeMs,"after_ms",afterMs,"max_frames",maxFrames,"max_gap_ms",maxGapMs);}
    public record Position(UUID frameId,Long millis,boolean ambiguous){}
    public record Segment(List<UUID> frameIds,String reason){public boolean eligible(){return reason.equals("sampled_sequence");}}
    /** Only the anchor's contiguous unambiguous segment may become an input. */
    public Segment segment(List<Position> positions,UUID anchor){
        int at=-1;
        for(int i=0;i<positions.size();i++){
            var current=positions.get(i);
            if(current.frameId().equals(anchor))at=i;
            if(i>0&&current.millis()!=null&&positions.get(i-1).millis()!=null&&current.millis()<positions.get(i-1).millis())throw new IllegalArgumentException("Positions are unordered");
        }
        if(at<0)throw new IllegalArgumentException("Anchor is missing");
        var center=positions.get(at);
        if(center.millis()==null)return new Segment(List.of(anchor),"clock_unavailable");
        if(center.ambiguous())return new Segment(List.of(anchor),"ambiguous_time");
        int first=at,last=at;
        while(first>0&&connected(positions.get(first-1),positions.get(first)))first--;
        while(last+1<positions.size()&&connected(positions.get(last),positions.get(last+1)))last++;
        var ids=positions.subList(first,last+1).stream().map(Position::frameId).toList();
        return new Segment(ids,ids.size()>1?"sampled_sequence":"insufficient_nearby_frames");
    }
    private boolean connected(Position a,Position b){
        return !a.ambiguous()&&!b.ambiguous()&&a.millis()!=null&&b.millis()!=null&&b.millis()>a.millis()&&b.millis()-a.millis()<=maxGapMs;
    }
}
