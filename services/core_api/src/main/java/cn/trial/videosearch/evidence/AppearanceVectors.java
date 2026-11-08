package cn.trial.videosearch.evidence;

import java.util.*;

/** Shared numeric contract for trusted local encoders and replaceable indices. */
public final class AppearanceVectors {
    private AppearanceVectors(){}
    public static void validate(List<Double> vector,int dimensions){
        if(vector==null||vector.size()!=dimensions)throw new IllegalArgumentException("Invalid appearance dimensions");
        double norm=0;for(Double value:vector){if(value==null||!Double.isFinite(value)||Math.abs(value)>1.0001)throw new IllegalArgumentException("Invalid appearance component");norm+=value*value;}
        if(Math.abs(norm-1)>0.002)throw new IllegalArgumentException("Appearance vector must be normalized");
    }
    public static double cosine(List<Double> query,float[] candidate){
        if(query.size()!=candidate.length)throw new IllegalArgumentException();double dot=0,norm=0;
        for(int i=0;i<candidate.length;i++){float value=candidate[i];if(!Float.isFinite(value))throw new IllegalArgumentException();dot+=query.get(i)*value;norm+=value*value;}
        if(Math.abs(norm-1)>0.002)throw new IllegalArgumentException();return Math.max(-1,Math.min(1,dot/Math.sqrt(norm)));
    }
}
