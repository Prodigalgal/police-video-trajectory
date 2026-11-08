package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SpecialistAdmissionTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final UUID FRAME=UUID.randomUUID(),RUN=UUID.randomUUID(),TARGET=UUID.randomUUID(),TRACK=UUID.randomUUID();
    private static final String SHA="a".repeat(64),POLICY="b".repeat(64);
    private Map<String,Object> descriptor(){return Map.of("frame_id",FRAME.toString(),"camera_id","recording-01","evidence_sha256",SHA,"operation","license_plate","image_width",1000,"image_height",600);}
    private Map<String,Object> selection(){
        var selection=new LinkedHashMap<String,Object>();selection.putAll(Map.of("schema_version","specialist-selection-v2","policy_sha256",POLICY,"mode","select","state","complete","frame_id",FRAME.toString(),"jpeg_sha256",SHA,"camera_id","recording-01","scene_run_id",RUN.toString(),"media_epoch",0,"relative_media_ms",1000));
        selection.putAll(Map.of("image_width",1000,"image_height",600,"frame_suppression",false,"semantic_modules_changed",false,"original_targets_changed",false));
        var candidate=new LinkedHashMap<String,Object>(Map.of("target_id",TARGET.toString(),"track_id",TRACK.toString(),"decision","selected","business_state","need_read","intent","collect_vehicle_passages","event_id",SpecialistAdmission.eventId("recording-01",RUN.toString(),0,TRACK.toString()),
            "plate_evidence",Map.of("schema_version","plate-evidence-v1","adapter","opencv_plate_v1","model_sha256","c".repeat(64),"association","unique_spatial_containment","frame_id",FRAME.toString(),"jpeg_sha256",SHA,"bbox_xyxy",List.of(100,200,200,240),"localization_score",.9),
            "representative",Map.of("frame_id",FRAME.toString(),"jpeg_sha256",SHA)));
        candidate.putAll(Map.of("passage_evidence","moving_observed","quality_scope","localized_plate_not_character_correctness","native_width_px",100,"native_height_px",40,"readability_rank",.8));
        selection.put("modules",List.of(new LinkedHashMap<>(Map.of("module","license_plate","decision","selected","business_state","need_read","reasons",List.of("first_usable_candidate"),"selected_target_ids",List.of(TARGET.toString()),"targets",List.of(candidate))),Map.of("module","face_quality","decision","deferred","reasons",List.of("face_module_not_implemented"),"identity_matching",false,"operation_available",false)));
        return selection;
    }
    private SpecialistAdmission service(Map<String,Object> selection,JdbcTemplate db){
        var target=Map.of("target_id",TARGET.toString(),"track_id",TRACK.toString(),"object_type","vehicle","class_name","car","association","local_geometry_candidate","bbox_xyxy",List.of(0,0,600,500));
        var plan=Map.of("image_width",1000,"image_height",600,"targets",List.of(target),"selection_metrics",Map.of("specialist_selection",selection));
        Map<String,Object> source=Map.of("frame_id",FRAME,"camera_id","recording-01","scene_run_id",RUN,"media_epoch",0L,"relative_media_ms",1000L,"payload_sha256",SHA,"plan_text",JSON.writeValueAsString(plan));
        when(db.queryForList(anyString(),eq(FRAME))).thenReturn(List.of(source));
        when(db.queryForObject(anyString(),eq(Long.class),eq("recording-01"),eq(5))).thenReturn(0L);
        var events=mock(PlateEventResults.class);when(events.assess(anyMap(),anyMap())).thenAnswer(call->call.getArgument(0));
        return new SpecialistAdmission(db,true,POLICY,5,2,events);
    }
    private ProcessingCatalog.Binding binding(){return new ProcessingCatalog.Binding("plate",null,null,"",0);}
    @Test void sourceBoundSelectedCandidateIsEligibleButNotIdentity(){
        var db=mock(JdbcTemplate.class);var admission=service(selection(),db);var preview=admission.preview(descriptor(),binding());
        assertEquals("selected",preview.get("decision"));assertEquals(false,preview.get("identity_association_verified"));
        assertEquals(false,preview.get("recommendation_is_inference_completion"));
    }
    @Test @SuppressWarnings("unchecked") void genuinePythonSelectionWithFloatPixelSpansRemainsEligible()throws Exception{
        Map<String,Object> replay;
        try(var stream=getClass().getResourceAsStream("/fixtures/real-specialist-selection-v2.json")){
            org.junit.jupiter.api.Assumptions.assumeTrue(stream!=null,"私有真实点位回放集不随公开源码发布");replay=JSON.readValue(stream,Map.class);
        }
        var plan=(Map<String,Object>)replay.get("plan");var metrics=(Map<String,Object>)plan.get("selection_metrics");
        var selected=(Map<String,Object>)metrics.get("specialist_selection");String policy=(String)selected.get("policy_sha256");
        UUID frame=UUID.fromString((String)replay.get("frame_id"));String camera=(String)replay.get("camera_id");
        Map<String,Object> source=Map.of("frame_id",frame,"camera_id",camera,"scene_run_id",UUID.fromString((String)selected.get("scene_run_id")),
            "media_epoch",selected.get("media_epoch"),"relative_media_ms",selected.get("relative_media_ms"),
            "payload_sha256",replay.get("image_sha256"),"plan_text",JSON.writeValueAsString(plan));
        var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq(frame))).thenReturn(List.of(source));
        when(db.queryForObject(anyString(),eq(Long.class),eq(camera),eq(5))).thenReturn(0L);
        var events=mock(PlateEventResults.class);when(events.assess(anyMap(),anyMap())).thenAnswer(call->call.getArgument(0));
        var admission=new SpecialistAdmission(db,true,policy,5,2,events);
        var descriptor=Map.<String,Object>of("frame_id",frame.toString(),"camera_id",camera,"evidence_sha256",replay.get("image_sha256"),
            "operation","license_plate","image_width",plan.get("image_width"),"image_height",plan.get("image_height"));
        assertEquals("selected",admission.preview(descriptor,binding()).get("decision"));
    }
    @ParameterizedTest @ValueSource(doubles={100.5,-100,20001,Double.NaN,Double.POSITIVE_INFINITY})
    @SuppressWarnings("unchecked") void fractionalOrUnboundedNativePixelSpanCannotAdmit(double span){
        var selected=selection();var module=(Map<String,Object>)((List<?>)selected.get("modules")).getFirst();
        ((Map<String,Object>)((List<?>)module.get("targets")).getFirst()).put("native_width_px",span);
        var admission=service(selected,mock(JdbcTemplate.class));
        assertEquals("unavailable",admission.preview(descriptor(),binding()).get("decision"));
    }
    @ParameterizedTest @ValueSource(strings={"jpeg_sha256","frame_id","camera_id","scene_run_id","media_epoch","policy_sha256","mode"})
    void changedSourcePolicyOrObserveModeDoesNotAdmit(String field){
        var selection=selection();selection.put(field,field.equals("media_epoch")?2:field.equals("mode")?"observe":"changed");
        var db=mock(JdbcTemplate.class);var admission=service(selection,db);
        assertEquals("unavailable",admission.preview(descriptor(),binding()).get("decision"));
        assertEquals("specialist_selection_unavailable",assertThrows(InputRejected.class,()->admission.approve(descriptor(),binding())).code);
    }
    @Test void budgetIsCheckedOnlyAfterValidSelection(){
        var db=mock(JdbcTemplate.class);var admission=service(selection(),db);
        when(db.queryForObject(anyString(),eq(Long.class),eq("recording-01"),eq(5))).thenReturn(2L);
        assertEquals("specialist_selection_budget",assertThrows(InputRejected.class,()->admission.approve(descriptor(),binding())).code);
    }
    @ParameterizedTest @ValueSource(strings={"quality_scope","native_width_px","readability_rank","passage_evidence","event_id","localization_score","plate_source"})
    @SuppressWarnings("unchecked") void plateAndPassageFactsCannotBeReplacedByWholeVehicleQuality(String fault){
        var selection=selection();var module=(Map<String,Object>)((List<?>)selection.get("modules")).getFirst();
        var candidate=(Map<String,Object>)((List<?>)module.get("targets")).getFirst();
        var plate=new LinkedHashMap<>((Map<String,Object>)candidate.get("plate_evidence"));candidate.put("plate_evidence",plate);
        switch(fault){
            case "quality_scope"->candidate.put(fault,"whole_vehicle");
            case "native_width_px"->candidate.put(fault,600);
            case "readability_rank"->candidate.put(fault,Double.NaN);
            case "passage_evidence"->candidate.put(fault,"stationary");
            case "event_id"->candidate.put(fault,UUID.randomUUID().toString());
            case "localization_score"->plate.put(fault,2);
            default->plate.put("jpeg_sha256","f".repeat(64));
        }
        var admission=service(selection,mock(JdbcTemplate.class));
        assertEquals("unavailable",admission.preview(descriptor(),binding()).get("decision"));
    }
    @Test void databaseFailureIsUnavailableInsteadOfNoCandidate(){
        var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq(FRAME))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unit failure"));
        var admission=new SpecialistAdmission(db,true,POLICY,5,2,new PlateEventResults(db,3));
        assertEquals(503,assertThrows(InputRejected.class,()->admission.preview(descriptor(),binding())).status);
    }
    @Test void disabledAdmissionDoesNotReadSelectionOrBudget(){
        var db=mock(JdbcTemplate.class);assertNull(new SpecialistAdmission(db,false,"",5,2,new PlateEventResults(db,3)).approve(descriptor(),binding()));verifyNoInteractions(db);
    }
}
