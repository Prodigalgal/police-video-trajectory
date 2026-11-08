package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistCandidatePolicyTest {
    @Test void singleConstructorBindsSpringPropertiesAndKeepsDefaultDisabled(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("candidate-test",Map.of("VIDEO_SPECIALIST_CANDIDATE_PENDING_LIMIT","40","VIDEO_SPECIALIST_CANDIDATE_BATCH","4")));
            context.register(SpecialistCandidatePolicy.class);context.refresh();
            var policy=context.getBean(SpecialistCandidatePolicy.class);
            assertFalse(policy.enabled());assertEquals(40,policy.pendingLimit());assertEquals(4,policy.batchSize());
        }
    }
    @Test void invalidPoliciesFailBeforeScheduling(){
        for(int[] values:new int[][]{{0,1,1,120,5,900},{500,51,1,120,5,900},{1,2,1,120,5,900},{500,20,5,120,5,900},{500,20,1,29,5,900},{500,20,1,120,11,900},{500,20,1,120,5,59}})
            assertThrows(IllegalArgumentException.class,()->new SpecialistCandidatePolicy(true,values[0],values[1],values[2],values[3],values[4],values[5]));
    }
    @Test void infrastructureBackoffIsMonotonicAndBounded(){
        var policy=new SpecialistCandidatePolicy(true,500,20,1,120,5,900);
        int previous=0;
        for(int count=1;count<=100;count++){int delay=policy.retrySeconds(count);assertTrue(delay>=previous&&delay<=300);previous=delay;}
        assertEquals(5,policy.retrySeconds(1));assertEquals(300,policy.retrySeconds(100));
    }
}
