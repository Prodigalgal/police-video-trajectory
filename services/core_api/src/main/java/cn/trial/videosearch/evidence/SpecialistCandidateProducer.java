package cn.trial.videosearch.evidence;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.trial.videosearch.evidence.SpecialistCandidateState.*;

/** Calls existing applications outside the candidate lease/write transaction. */
@Service
public class SpecialistCandidateProducer {
    private static final String ACTOR="specialist-producer";
    private static final Set<String> INVALID_SOURCE=Set.of("candidate_source_changed","input_source_changed","input_manifest_changed","input_evidence_changed",
        "observation_revision_conflict","input_unavailable","observation_unavailable","input_source_unavailable","specialist_input_changed");
    private static final Set<String> CONFIGURATION=Set.of("specialist_not_configured","specialist_disabled","specialist_module_disabled",
        "processing_catalog_unavailable","processing_profile_conflict","model_catalog_source_changed","model_catalog_unavailable");
    private final SpecialistCandidates candidates;
    private final AnalysisInputs inputs;
    private final SpecialistJobs jobs;
    private final SpecialistCandidatePolicy policy;
    private final String owner="candidate-"+UUID.randomUUID();
    public SpecialistCandidateProducer(SpecialistCandidates candidates,AnalysisInputs inputs,SpecialistJobs jobs,SpecialistCandidatePolicy policy){
        this.candidates=candidates;this.inputs=inputs;this.jobs=jobs;this.policy=policy;
    }
    public int tick(){
        if(!candidates.running())return 0;
        candidates.scan();int processed=0;
        for(int count=0;count<policy.batchSize();count++){
            var lease=candidates.claim(owner);if(lease.isEmpty())break;
            process(lease.get());processed++;
        }
        return processed;
    }
    public void process(SpecialistCandidates.Lease lease){
        try{
            var source=candidates.source(lease);
            if(source.reason()!=null){finish(lease,source.invalid()?INVALID:WAITING_SOURCE,source.reason(),source.invalid()?0:5,null,false);return;}
            String manifestSha=null;
            if(lease.input()!=null){
                manifestSha=inputs.metadata(lease.input()).sha256();
                var previous=jobs.replayCreate(lease.input(),new SpecialistJobs.Create(SpecialistCandidates.request(lease.id(),"job"),manifestSha,"license_plate"),ACTOR);
                if(previous.isPresent()){
                    finish(lease,SUBMITTED,"specialist_job_registered",0,UUID.fromString((String)previous.get().get("job_id")),false);return;
                }
            }
            var capabilities=jobs.capabilities();
            if(!Boolean.TRUE.equals(capabilities.get("configured"))||!(capabilities.get("operations") instanceof List<?> operations)||!operations.contains("license_plate")){
                finish(lease,WAITING_CONFIGURATION,"specialist_not_configured",30,null,false);return;
            }
            if(!candidates.bindAnchor(lease,source))return;
            UUID inputId=lease.input();
            if(inputId==null){
                var input=inputs.create(new AnalysisInputs.Create(SpecialistCandidates.request(lease.id(),"input"),source.observation(),source.version(),lease.evidenceSha(),"single_frame",null),ACTOR);
                inputId=UUID.fromString((String)input.get("input_id"));manifestSha=(String)input.get("manifest_sha256");
                if(!candidates.bindInput(lease,inputId))return;
            }
            var selection=jobs.selection(inputId);
            if(!"selected".equals(selection.get("decision"))){selectionOutcome(lease,selection);return;}
            var job=jobs.create(inputId,new SpecialistJobs.Create(SpecialistCandidates.request(lease.id(),"job"),manifestSha,"license_plate"),ACTOR);
            finish(lease,SUBMITTED,"specialist_job_registered",0,UUID.fromString((String)job.get("job_id")),false);
        }catch(InputRejected failure){
            if(INVALID_SOURCE.contains(failure.code))finish(lease,INVALID,failure.code,0,null,false);
            else if(CONFIGURATION.contains(failure.code))finish(lease,WAITING_CONFIGURATION,failure.code,30,null,false);
            else if("specialist_selection_budget".equals(failure.code)||"specialist_queue_full".equals(failure.code))finish(lease,WAITING_BUDGET,failure.code,5,null,false);
            else if(Set.of("specialist_not_selected","specialist_result_reusable").contains(failure.code))finish(lease,WAITING_RESULT,"selection_changed_during_admission",5,null,false);
            else if("input_evidence_unavailable".equals(failure.code))retryFailure(lease,"input_evidence_unavailable");
            else retryFailure(lease,"candidate_submission_unavailable");
        }catch(DataAccessException unavailable){
            // If this acknowledgement also fails, expiry will reclaim the lease;
            // stable input/job receipts still prevent another task admission.
            retryFailure(lease,"candidate_database_unavailable");
        }catch(IllegalArgumentException invalid){finish(lease,INVALID,"candidate_contract_invalid",0,null,false);}
    }
    private void selectionOutcome(SpecialistCandidates.Lease lease,Map<String,Object> selection){
        String state=String.valueOf(selection.get("business_state"));
        switch(state){
            case "reuse_result"->{
                var reference=SpecialistResult.object(selection.get("reuse_reference"));
                finish(lease,REUSED,"confirmed_event_result_available",0,UUID.fromString((String)reference.get("job_id")),false);
            }
            case "budget_wait"->finish(lease,WAITING_BUDGET,"camera_budget_wait",5,null,false);
            case "waiting_result"->finish(lease,WAITING_RESULT,"event_job_pending",5,null,false);
            case "pending_review"->finish(lease,WAITING_REVIEW,"event_result_awaiting_review",15,null,false);
            case "not_applicable","unreadable"->finish(lease,SKIPPED,"event_no_new_read_required",0,null,false);
            default->{
                var reasons=selection.get("reasons") instanceof List<?> values?values:List.of();
                if(reasons.contains("selection_evidence_invalid"))finish(lease,INVALID,"selection_evidence_invalid",0,null,false);
                else if(reasons.contains("selection_policy_unavailable")||reasons.contains("selection_not_applied"))finish(lease,WAITING_CONFIGURATION,"selection_policy_unavailable",30,null,false);
                else finish(lease,WAITING_SOURCE,"selection_evidence_pending",5,null,false);
            }
        }
    }
    private void retryFailure(SpecialistCandidates.Lease lease,String reason){
        int failures=lease.failures()+1;finish(lease,failures>=policy.maxFailures()?FAILED:READY,reason,policy.retrySeconds(failures),null,true);
    }
    private void finish(SpecialistCandidates.Lease lease,SpecialistCandidateState state,String reason,int delay,UUID job,boolean failure){
        candidates.finish(lease,state,reason,delay,job,failure);
    }
}
