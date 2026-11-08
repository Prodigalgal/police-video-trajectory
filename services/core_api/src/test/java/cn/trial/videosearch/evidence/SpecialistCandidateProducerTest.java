package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.trial.videosearch.evidence.SpecialistCandidateState.*;

class SpecialistCandidateProducerTest {
    private final SpecialistCandidates candidates=mock(SpecialistCandidates.class);
    private final AnalysisInputs inputs=mock(AnalysisInputs.class);
    private final SpecialistJobs jobs=mock(SpecialistJobs.class);
    private final SpecialistCandidatePolicy policy=new SpecialistCandidatePolicy(true,500,20,1,120,5,900);
    private final SpecialistCandidateProducer producer=new SpecialistCandidateProducer(candidates,inputs,jobs,policy);
    private final UUID id=UUID.randomUUID(),frame=UUID.randomUUID(),target=UUID.randomUUID(),observation=UUID.randomUUID(),input=UUID.randomUUID(),job=UUID.randomUUID();
    private final String sha="a".repeat(64);
    private SpecialistCandidates.Lease lease(UUID savedInput,int failures){return new SpecialistCandidates.Lease(id,frame,"recording-01",target,sha,"b".repeat(64),"test-worker",1,savedInput==null?null:observation,savedInput==null?null:1L,savedInput,failures);}
    private void available(SpecialistCandidates.Lease lease){
        when(candidates.source(lease)).thenReturn(new SpecialistCandidates.Source(observation,1,null,false));
        when(candidates.bindAnchor(eq(lease),any())).thenReturn(true);when(candidates.bindInput(lease,input)).thenReturn(true);
        when(jobs.capabilities()).thenReturn(Map.of("configured",true,"operations",List.of("license_plate")));
        when(inputs.create(any(),eq("specialist-producer"))).thenReturn(Map.of("input_id",input.toString(),"manifest_sha256",sha));
        when(inputs.metadata(input)).thenReturn(new AnalysisInputs.Verified(Map.of(),"",sha,null));
        when(jobs.selection(input)).thenReturn(Map.of("decision","selected","business_state","need_read"));
        when(jobs.create(eq(input),any(),eq("specialist-producer"))).thenReturn(Map.of("job_id",job.toString()));
    }
    @Test void disabledProducerDoesNotScanOrCallApplications(){
        assertEquals(0,producer.tick());verify(candidates,never()).scan();verifyNoInteractions(inputs,jobs);
    }
    @Test void sourcePendingDoesNotCreateInputOrTask(){
        var lease=lease(null,0);when(candidates.source(lease)).thenReturn(new SpecialistCandidates.Source(null,0,"main_analysis_pending",false));
        producer.process(lease);verify(candidates).finish(lease,WAITING_SOURCE,"main_analysis_pending",5,null,false);verifyNoInteractions(inputs,jobs);
    }
    @Test void withdrawnSourceTerminatesBeforeConfigurationOrInputReads(){
        var lease=lease(null,0);when(candidates.source(lease)).thenReturn(new SpecialistCandidates.Source(null,0,"candidate_source_withdrawn",true));
        producer.process(lease);verify(candidates).finish(lease,INVALID,"candidate_source_withdrawn",0,null,false);verifyNoInteractions(inputs,jobs);
    }
    @Test void missingModelConfigurationDoesNotCreatePlaceholderInputOrFakeSuccess(){
        var lease=lease(null,0);available(lease);when(jobs.capabilities()).thenReturn(Map.of("configured",false,"operations",List.of()));
        producer.process(lease);verify(candidates).finish(lease,WAITING_CONFIGURATION,"specialist_not_configured",30,null,false);verifyNoInteractions(inputs);
    }
    @Test void successfulSubmissionUsesStableSeparateReceiptsAndDoesNotClaimRecognition(){
        var lease=lease(null,0);available(lease);producer.process(lease);
        verify(inputs).create(eq(new AnalysisInputs.Create(SpecialistCandidates.request(id,"input"),observation,1L,sha,"single_frame",null)),eq("specialist-producer"));
        verify(jobs).create(input,new SpecialistJobs.Create(SpecialistCandidates.request(id,"job"),sha,"license_plate"),"specialist-producer");
        verify(candidates).finish(lease,SUBMITTED,"specialist_job_registered",0,job,false);
        assertNotEquals(SpecialistCandidates.request(id,"input"),SpecialistCandidates.request(id,"job"));
    }
    @Test void lostAcknowledgementReplaysBeforeReadingChangedOrBrokenConfiguration(){
        var lease=lease(input,0);available(lease);when(jobs.replayCreate(eq(input),any(),eq("specialist-producer"))).thenReturn(Optional.of(Map.of("job_id",job.toString())));
        producer.process(lease);verify(jobs,never()).capabilities();verify(jobs,never()).selection(any());verify(jobs,never()).create(any(),any(),any());
        verify(candidates).finish(lease,SUBMITTED,"specialist_job_registered",0,job,false);
    }
    @ParameterizedTest @ValueSource(strings={"specialist_selection_budget","specialist_queue_full"})
    void admissionRaceRetainsTheCandidateWithoutChargingAnInfrastructureFailure(String code){
        var lease=lease(null,0);available(lease);when(jobs.create(eq(input),any(),anyString())).thenThrow(new InputRejected(429,code));
        producer.process(lease);verify(candidates).finish(lease,WAITING_BUDGET,code,5,null,false);
    }
    @Test void budgetPreviewDoesNotAttemptTaskCreation(){
        var lease=lease(null,0);available(lease);when(jobs.selection(input)).thenReturn(Map.of("decision","deferred","business_state","budget_wait"));
        producer.process(lease);verify(candidates).finish(lease,WAITING_BUDGET,"camera_budget_wait",5,null,false);verify(jobs,never()).create(any(),any(),any());
    }
    @Test void confirmedResultIsAnOriginalTaskReference(){
        var lease=lease(null,0);available(lease);when(jobs.selection(input)).thenReturn(Map.of("decision","reused","business_state","reuse_result","reuse_reference",Map.of("job_id",job.toString())));
        producer.process(lease);verify(candidates).finish(lease,REUSED,"confirmed_event_result_available",0,job,false);verify(jobs,never()).create(any(),any(),any());
    }
    @Test void staleLeaseCannotBindSourceAndStartNewWork(){
        var lease=lease(null,0);available(lease);when(candidates.bindAnchor(eq(lease),any())).thenReturn(false);
        producer.process(lease);verifyNoInteractions(inputs);verify(jobs,never()).create(any(),any(),any());verify(candidates,never()).finish(any(),any(),any(),anyInt(),any(),anyBoolean());
    }
    @Test void temporaryFileFailureHasFiniteBackoffAndExhaustion(){
        var lease=lease(null,4);available(lease);when(inputs.create(any(),anyString())).thenThrow(new InputRejected(503,"input_evidence_unavailable"));
        producer.process(lease);verify(candidates).finish(lease,FAILED,"input_evidence_unavailable",80,null,true);
        assertEquals(300,policy.retrySeconds(100));
    }
    @Test void observationVersionChangeIsNotRetriedAsAnOldVehicle(){
        var lease=lease(null,0);available(lease);when(inputs.create(any(),anyString())).thenThrow(new InputRejected(409,"observation_revision_conflict"));
        producer.process(lease);verify(candidates).finish(lease,INVALID,"observation_revision_conflict",0,null,false);
    }
}
