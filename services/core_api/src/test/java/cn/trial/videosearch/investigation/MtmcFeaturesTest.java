package cn.trial.videosearch.investigation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MtmcFeaturesTest {
    private static MtmcFeatures.Feature feature(double first,double second){return new MtmcFeatures.Feature(List.of(first,second),"source","vector","content");}
    @Test void centroidNormalizesSeveralObservationsInOneFeatureSpace(){
        var vector=MtmcFeatures.centroid(List.of(feature(1,0),feature(0,1)),2);
        assertEquals(Math.sqrt(.5),vector.get(0),.000001);assertEquals(Math.sqrt(.5),vector.get(1),.000001);
        assertEquals(List.of(1.,0.),MtmcFeatures.centroid(List.of(feature(1,0)),2));
    }
    @Test void incoherentLocalFeaturesFallBackToAnchorRatherThanAnInvalidZeroVector(){
        assertEquals(List.of(1.,0.),MtmcFeatures.centroid(List.of(feature(1,0),feature(-1,0)),2));
        assertThrows(IllegalArgumentException.class,()->MtmcFeatures.centroid(List.of(),2));
        assertThrows(IllegalArgumentException.class,()->MtmcFeatures.centroid(Collections.nCopies(6,feature(1,0)),2));
        assertThrows(IllegalArgumentException.class,()->MtmcFeatures.centroid(List.of(feature(1,0)),3));
    }
}
