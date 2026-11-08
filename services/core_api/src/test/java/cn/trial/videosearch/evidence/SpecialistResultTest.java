package cn.trial.videosearch.evidence;

import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistResultTest {
    private Map<String,Object> input(){return Map.of("frame_id",UUID.randomUUID().toString(),"evidence_sha256","a".repeat(64),"operation","license_plate","profile","protocol-test-v1","image_width",1280,"image_height",960);}
    private Map<String,Object> result(UUID job,Map<String,Object> input){var value=new LinkedHashMap<>(input);value.putAll(Map.of("schema_version","specialist-result-v1","job_id",job.toString(),"input_sha256","b".repeat(64),"engine",Map.of("name","protocol-fixture","version","not-accuracy-evidence"),"outcome","unreadable","regions",List.of(),"limitations",List.of("low_resolution")));return value;}
    @Test void dedicatedOutcomeDoesNotConflateUnseenAndUnreadable(){
        UUID job=UUID.randomUUID();var input=input();var value=result(job,input);String before=SpecialistResult.validate(job,"b".repeat(64),input,value).sha256();value.put("outcome","no_text");assertNotEquals(before,SpecialistResult.validate(job,"b".repeat(64),input,value).sha256());value.put("outcome","recognized");assertEquals("invalid_specialist_result",assertThrows(InputRejected.class,()->SpecialistResult.validate(job,"b".repeat(64),input,value)).code);
    }
    @Test void unknownFieldsWrongJobBadBoxesAndNaNAreRejected(){
        UUID job=UUID.randomUUID();var input=input();var value=result(job,input);value.put("extra",true);assertThrows(InputRejected.class,()->SpecialistResult.validate(job,"b".repeat(64),input,value));value.remove("extra");value.put("job_id",UUID.randomUUID().toString());assertThrows(InputRejected.class,()->SpecialistResult.validate(job,"b".repeat(64),input,value));value.put("job_id",job.toString());value.put("outcome","recognized");
        for(var box:List.of(List.of(0,0,1281,10),List.of(0,0,0,10),List.of(0.0,0,10,10))){value.put("regions",List.of(Map.of("index",0,"text","协议字段","bbox_xyxy",box,"score",.9)));assertThrows(InputRejected.class,()->SpecialistResult.validate(job,"b".repeat(64),input,value));}
        value.put("regions",List.of(Map.of("index",0,"text","协议字段","bbox_xyxy",List.of(0,0,10,10),"score",Double.NaN)));assertThrows(InputRejected.class,()->SpecialistResult.validate(job,"b".repeat(64),input,value));
    }
    @Test void disabledOrUnconfiguredServiceCreatesNoWorkAndReadsNoPixels(){
        var db=mock(JdbcTemplate.class);var inputs=mock(AnalysisInputs.class);var search=mock(SearchOrchestrator.class);var manager=mock(PlatformTransactionManager.class);var create=new SpecialistJobs.Create(UUID.randomUUID(),"a".repeat(64),"license_plate");
        var disabled=new SpecialistJobs(db,inputs,search,manager,false,"",100,1,3,new ProcessingCatalog(""),new SpecialistAdmission(db,false,"",5,2,new PlateEventResults(db,3)),mock(PlateEventResults.class));assertEquals("specialist_disabled",assertThrows(InputRejected.class,()->disabled.create(UUID.randomUUID(),create,"reader")).code);verifyNoInteractions(db,inputs,search,manager);
        var unconfigured=new SpecialistJobs(db,inputs,search,manager,true,"",100,1,3,new ProcessingCatalog(""),new SpecialistAdmission(db,false,"",5,2,new PlateEventResults(db,3)),mock(PlateEventResults.class));assertEquals("specialist_not_configured",assertThrows(InputRejected.class,()->unconfigured.create(UUID.randomUUID(),create,"reader")).code);verifyNoInteractions(inputs,manager);
    }
    @Test void nullCapabilitiesAndNullOperationsDoNotBecomeServerFailures(){
        var jobs=new SpecialistJobs(mock(JdbcTemplate.class),mock(AnalysisInputs.class),mock(SearchOrchestrator.class),mock(PlatformTransactionManager.class),true,"profile",100,1,3,new ProcessingCatalog(""),mock(SpecialistAdmission.class),mock(PlateEventResults.class));
        assertThrows(IllegalArgumentException.class,()->jobs.claim("worker",120,"profile",Arrays.asList((String)null)));
        assertThrows(IllegalArgumentException.class,()->jobs.create(UUID.randomUUID(),new SpecialistJobs.Create(UUID.randomUUID(),"a".repeat(64),null),"reader"));
    }
    @Test void declaredUncertainPlateCannotBecomeRecognizedEvenWithHighModelScore(){
        UUID job=UUID.randomUUID();var input=input();var value=result(job,input);value.put("outcome","recognized");value.put("regions",List.of(Map.of("index",0,"text","协议字段","bbox_xyxy",List.of(0,0,100,20),"score",.999)));
        for(String flag:List.of("uncertain_characters","partial_text","inconsistent_readings")){value.put("limitations",List.of(flag));assertThrows(InputRejected.class,()->SpecialistResult.validate(job,"b".repeat(64),input,value));}
        value.put("outcome","unreadable");value.put("regions",List.of());SpecialistResult.validate(job,"b".repeat(64),input,value);
    }
}
