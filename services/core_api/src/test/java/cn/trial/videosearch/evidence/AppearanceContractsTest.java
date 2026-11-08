package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceContractsTest {
    @TempDir Path temp;
    @Test void vectorsRejectUnnormalizedNonfiniteAndWrongSpaces(){
        assertDoesNotThrow(()->AppearanceVectors.validate(List.of(1.0,0.0),2));
        assertThrows(IllegalArgumentException.class,()->AppearanceVectors.validate(List.of(0.0,0.0),2));
        assertThrows(IllegalArgumentException.class,()->AppearanceVectors.validate(List.of(Double.NaN,0.0),2));
        assertThrows(IllegalArgumentException.class,()->AppearanceVectors.validate(List.of(1.0),2));
        assertEquals(.8,AppearanceVectors.cosine(List.of(1.0,0.0),new float[]{.8f,.6f}),.000001);
    }
    @Test void boxesRejectSmallOutsideAndNonfinite(){
        assertDoesNotThrow(()->AppearanceSource.validateBox(List.of(0.,0.,12.,12.),100,100));
        for(var box:List.of(List.of(-1.,0.,20.,20.),List.of(0.,0.,11.,20.),List.of(0.,0.,200.,20.),List.of(0.,Double.NaN,20.,20.)))assertThrows(IllegalArgumentException.class,()->AppearanceSource.validateBox(box,100,100));
    }
    private static AppearanceIndex.Ranked hit(String camera,String run,String track,long position,double score){
        return new AppearanceIndex.Ranked(Map.of("observation_id",UUID.randomUUID().toString(),"camera_id",camera),Map.of("task_id","task","scene_run_id",run,"media_epoch",0,"relative_media_ms",position),track,score);
    }
    @Test void periodsSplitCameraSessionGapAndRetainScoreOrder(){
        var a=hit("a","run","track",0,.8);var b=hit("a","run","track",1000,.9);var gap=hit("a","run","track",7000,.7);
        var otherCamera=hit("b","run","track",1000,.6);var otherRun=hit("a","next","track",1000,.5);
        var groups=AppearanceSearch.group(List.of(gap,b,otherCamera,a,otherRun));assertEquals(4,groups.size());
        var sightings=(List<Map<String,Object>>)groups.getFirst().get("sightings");assertEquals(2,sightings.size());
        var result=AppearanceSearch.external(sightings);assertEquals(.9,result.get("similarity"));assertEquals("relative_media",((Map<?,?>)result.get("period")).get("clock"));assertEquals(1000L,((Map<?,?>)result.get("period")).get("end_ms"));
    }
    @Test void unknownClockDoesNotInventMidnight(){
        var hit=new AppearanceIndex.Ranked(Map.of("observation_id",UUID.randomUUID().toString(),"camera_id","a"),Map.of(),"track",.9);
        var group=AppearanceSearch.group(List.of(hit)).getFirst();var result=AppearanceSearch.external((List<Map<String,Object>>)group.get("sightings"));
        var period=(Map<?,?>)result.get("period");assertEquals("unknown",period.get("clock"));assertNull(period.get("start_ms"));assertNull(period.get("end_ms"));
    }
    @Test void catalogIsBoundedAndCannotChangeProfileUnderExistingId() throws Exception {
        Path original=Path.of("../../deploy/appearance-catalog.json");String source=Files.readString(original);Path path=temp.resolve("catalog.json");Files.writeString(path,source);
        var catalog=new AppearanceCatalog(path.toString());var profile=catalog.current().selected();assertEquals(384,profile.dimensions());assertEquals("68a93a786848c4e99eb969bc081413a0560a21952f9f9ab55fa129c27a1efdd5",profile.sha256());
        Files.writeString(path,source.replace("\"revision\": 1","\"revision\": 2").replace("3afdc8bc63b50558d6e5770f5b799bb82455c2311183a2de43803f343a29d917","0".repeat(64)));
        assertThrows(InputRejected.class,catalog::current);
    }
}
