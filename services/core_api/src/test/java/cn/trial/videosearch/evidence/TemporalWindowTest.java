package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemporalWindowTest {
    final TemporalWindow window=TemporalWindow.defaults();
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
    TemporalWindow.Position p(UUID id,Long time,boolean ambiguous){return new TemporalWindow.Position(id,time,ambiguous);}
    @Test void boundsCannotBeRelaxedIntoMinuteLongSequences(){
        assertThrows(IllegalArgumentException.class,()->new TemporalWindow(120001,0,16,6000));
        assertThrows(IllegalArgumentException.class,()->new TemporalWindow(0,0,25,6000));
        assertThrows(IllegalArgumentException.class,()->new TemporalWindow(0,0,16,10001));
        assertThrows(IllegalArgumentException.class,()->new TemporalWindow(0,0,16,0));
        assertEquals(0,TemporalWindow.single().maxGapMs());
    }
    @Test void onlyTheAnchorsSegmentSurvivesAClockGap(){
        var segment=window.segment(List.of(p(a,0L,false),p(b,5000L,false),p(c,60000L,false),p(d,65000L,false)),b);
        assertTrue(segment.eligible());assertEquals(List.of(a,b),segment.frameIds());
    }
    @Test void sparseAndUnknownClocksNeverBorrowArrivalTimes(){
        assertEquals("insufficient_nearby_frames",window.segment(List.of(p(a,0L,false),p(b,60000L,false)),a).reason());
        assertEquals("clock_unavailable",window.segment(List.of(p(a,null,false)),a).reason());
    }
    @Test void ambiguousFramesOutsideThePageBreakASequenceToo(){
        assertEquals("ambiguous_time",window.segment(List.of(p(a,0L,true),p(b,5000L,false)),a).reason());
        assertFalse(window.segment(List.of(p(a,0L,true),p(b,5000L,false)),b).eligible());
        assertFalse(window.segment(List.of(p(a,0L,false),p(b,0L,false)),a).eligible());
    }
    @Test void missingAnchorAndReverseClocksAreRejected(){
        assertThrows(IllegalArgumentException.class,()->window.segment(List.of(p(a,5L,false)),b));
        assertThrows(IllegalArgumentException.class,()->window.segment(List.of(p(a,5L,false),p(b,1L,false)),a));
    }
}
