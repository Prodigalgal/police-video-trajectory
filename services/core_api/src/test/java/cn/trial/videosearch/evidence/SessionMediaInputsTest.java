package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMediaInputsTest {
    private Map<String,Object> descriptor(){var result=new LinkedHashMap<String,Object>();result.putAll(Map.of("version",SessionMediaInputs.VERSION,"source_id",UUID.randomUUID().toString(),"camera_id","recording-01","source_fingerprint","a".repeat(64),"namespace","b".repeat(32),"segment_id",UUID.randomUUID().toString(),"recording_manifest_sha256","c".repeat(64),"source_sha256","d".repeat(64),"source_bytes",100));result.putAll(Map.of("duration_us",5000000,"task_id",UUID.randomUUID().toString(),"task_generation",1,"scene_run_id",UUID.randomUUID().toString(),"media_epoch",0,"base_pts_us",123456789L));return result;}
    @Test void strictFrozenDescriptorCannotLoseClockOrUsePathsOrFloatingNumbers(){
        var original=descriptor();SessionMediaInputs.validateDescriptor(original);assertEquals(5000,SessionMediaInputs.endMs(original));
        for(var entry:Map.<String,Object>of("namespace","../other","duration_us",true,"source_bytes",16777217,"base_pts_us",Long.MAX_VALUE,"task_generation",1.0,"camera_id","../recording-01").entrySet()){
            var changed=new LinkedHashMap<>(original);changed.put(entry.getKey(),entry.getValue());assertEquals("media_session_changed",assertThrows(InputRejected.class,()->SessionMediaInputs.validateDescriptor(changed)).code);
        }
        var extra=new LinkedHashMap<>(original);extra.put("frame_id",UUID.randomUUID().toString());assertThrows(InputRejected.class,()->SessionMediaInputs.validateDescriptor(extra));
    }
    @Test void sessionInputHashRetainsSegmentClockButContainsNoSingleFrameIdentity(){
        var original=descriptor();String digest=SessionMediaInputs.inputSha(original);var changed=new LinkedHashMap<>(original);changed.put("base_pts_us",123456790L);assertNotEquals(digest,SessionMediaInputs.inputSha(changed));changed=new LinkedHashMap<>(original);changed.put("segment_id",UUID.randomUUID().toString());assertNotEquals(digest,SessionMediaInputs.inputSha(changed));assertTrue(SessionMediaInputs.same(original,new LinkedHashMap<>(original)));
    }
    @Test void frameEndpointsKeepWorkerTokenSeparateAndClaimCapabilityExplicit(){
        var jobs=mock(MediaJobs.class);var search=mock(SearchOrchestrator.class);when(search.authorized("Bearer reader")).thenReturn(true);var web=WebTestClient.bindToController(new MediaJobController(jobs,search,"worker-token")).build();UUID frame=UUID.randomUUID();var body=new MediaJobs.SessionCreate(UUID.randomUUID(),"a".repeat(64));
        web.post().uri("/api/v1/frames/"+frame+"/media-jobs").header("Authorization","Bearer worker-token").bodyValue(body).exchange().expectStatus().isUnauthorized();verifyNoInteractions(jobs);
        when(jobs.createSession(frame,body)).thenReturn(Map.of("state","queued"));web.post().uri("/api/v1/frames/"+frame+"/media-jobs").header("Authorization","Bearer reader").bodyValue(body).exchange().expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("queued");
        when(jobs.claim("worker",120,List.of("archive","session"))).thenReturn(Optional.empty());web.post().uri("/internal/v1/media-jobs/claim").header("Authorization","Bearer worker-token").bodyValue(Map.of("workerId","worker","leaseSeconds",120,"inputKinds",List.of("archive","session"))).exchange().expectStatus().isOk().expectBody().jsonPath("$.job_available").isEqualTo(false);verify(jobs).claim("worker",120,List.of("archive","session"));
    }
}
