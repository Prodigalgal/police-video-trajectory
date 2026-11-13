package cn.trial.videosearch.evidence;

import cn.trial.videosearch.admin.UserService;
import cn.trial.videosearch.ingest.WorkerController;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalysisInputsTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final TemporalEvidence evidence=mock(TemporalEvidence.class);
    final EvidenceFiles files=mock(EvidenceFiles.class);
    final SearchOrchestrator search=mock(SearchOrchestrator.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final AnalysisInputs inputs=new AnalysisInputs(db,evidence,files,search,manager);
    final UUID observation=UUID.randomUUID(),frame=UUID.randomUUID();
    final String digest="a".repeat(64);
    TemporalEvidence.Anchor fixture(String registered)throws Exception{
        var values=new LinkedHashMap<String,Object>();
        values.put("frame_id",frame);values.put("camera_id","recording-01");values.put("task_id",UUID.randomUUID());
        values.put("evidence_ref","evidence:"+frame.toString().replace("-",""));values.put("payload_sha256",registered);
        values.put("image_width","1280");values.put("image_height","720");values.put("source_type","rtsp");
        var anchor=new TemporalEvidence.Anchor(observation,1L,Map.of("target_type","vehicle","model_id","saved-model","prompt_version","saved-prompt"),new TemporalEvidence.Frame(values));
        when(evidence.anchor(observation,1L)).thenReturn(anchor);
        when(files.read(eq(frame),anyString())).thenReturn(new EvidenceFiles.FrameFile(new byte[]{1,2,3},1280,720,digest));
        return anchor;
    }
    AnalysisInputs.Create body(String purpose,String sha){return new AnalysisInputs.Create(UUID.randomUUID(),observation,1L,sha,purpose,null);}
    @Test void invalidRequestsDoNotTouchEvidenceOrTransactions(){
        assertThrows(IllegalArgumentException.class,()->inputs.create(body("unknown",digest),"reader"));
        assertThrows(IllegalArgumentException.class,()->inputs.create(body("single_frame","bad"),"reader"));
        verifyNoInteractions(evidence,files,manager);
    }
    @Test void browserDigestAndRegisteredDigestBothBindBeforeOpeningATransaction()throws Exception{
        fixture(digest);
        assertEquals("input_evidence_changed",assertThrows(InputRejected.class,()->inputs.create(body("single_frame","b".repeat(64)),"reader")).code);
        fixture("b".repeat(64));
        assertEquals("input_evidence_changed",assertThrows(InputRejected.class,()->inputs.create(body("single_frame",digest),"reader")).code);
        verifyNoInteractions(manager);
    }
    @Test void dimensionsAndMissingFilesAreNotSilentlyAccepted()throws Exception{
        fixture(digest);when(files.read(eq(frame),anyString())).thenReturn(new EvidenceFiles.FrameFile(new byte[]{1},640,360,digest));
        assertEquals("input_evidence_changed",assertThrows(InputRejected.class,()->inputs.create(body("single_frame",digest),"reader")).code);
        when(files.read(eq(frame),anyString())).thenReturn(null);
        assertEquals("input_evidence_unavailable",assertThrows(InputRejected.class,()->inputs.create(body("single_frame",digest),"reader")).code);
        verifyNoInteractions(manager);
    }
    @Test void sparseSequencesFailBeforeReadingAnyPixels()throws Exception{
        var anchor=fixture(digest);
        when(evidence.context(observation,1L,TemporalWindow.defaults())).thenReturn(new TemporalEvidence.Context(anchor,TemporalWindow.defaults(),List.of(anchor.frame()),new TemporalWindow.Segment(List.of(frame),"insufficient_nearby_frames"),false,Set.of()));
        assertEquals("input_sequence_unavailable",assertThrows(InputRejected.class,()->inputs.create(body("frame_sequence",digest),"reader")).code);
        verify(files,never()).read(any(),any());verifyNoInteractions(manager);
    }
    @Test void sourceIsRecheckedUnderAShortTransactionAfterFileIo()throws Exception{
        var anchor=fixture(null);var status=new SimpleTransactionStatus();when(manager.getTransaction(any())).thenReturn(status);
        when(files.read(eq(frame),anyString())).thenAnswer(call->{verifyNoInteractions(manager);return new EvidenceFiles.FrameFile(new byte[]{1},1280,720,digest);});
        doThrow(new InputRejected(409,"input_source_changed")).when(evidence).unchanged(eq(anchor),anyList());
        assertEquals("input_source_changed",assertThrows(InputRejected.class,()->inputs.create(body("single_frame",digest),"reader")).code);
        verify(evidence).lockSources(eq(anchor),anyList());verify(manager).rollback(status);
        verify(db,never()).update(startsWith("INSERT INTO analysis_inputs"),any(Object[].class));
    }
    @Test void aSequenceHasAnAggregateByteBudgetBeforeAnyWrite()throws Exception{
        var anchor=fixture(digest);var selected=new ArrayList<TemporalEvidence.Frame>();selected.add(anchor.frame());
        for(int number=1;number<10;number++){
            var values=new LinkedHashMap<>(anchor.frame().values());values.put("frame_id",UUID.randomUUID());selected.add(new TemporalEvidence.Frame(values));
        }
        var ids=selected.stream().map(TemporalEvidence.Frame::id).toList();
        when(evidence.context(observation,1L,TemporalWindow.defaults())).thenReturn(new TemporalEvidence.Context(anchor,TemporalWindow.defaults(),selected,new TemporalWindow.Segment(ids,"sampled_sequence"),false,Set.of()));
        var bytes=new byte[8*1024*1024];when(files.read(any(),anyString())).thenReturn(new EvidenceFiles.FrameFile(bytes,1280,720,digest));
        assertEquals("input_too_large",assertThrows(InputRejected.class,()->inputs.create(body("frame_sequence",digest),"reader")).code);
        verifyNoInteractions(manager);
    }
    @Test void manifestVerifiesAllImagesButImageReadsDoNotMultiplySequenceFileIo()throws Exception{
        var anchor=fixture(digest);UUID secondId=UUID.randomUUID(),inputId=UUID.randomUUID();
        var secondValues=new LinkedHashMap<>(anchor.frame().values());secondValues.put("frame_id",secondId);secondValues.put("evidence_ref","evidence:"+secondId.toString().replace("-",""));
        var second=new TemporalEvidence.Frame(secondValues);var selected=List.of(anchor.frame(),second);
        var savedFrames=selected.stream().map(frame->{var value=frame.view();value.put("payload_sha256",digest);value.put("image_width",1280);value.put("image_height",720);return value;}).toList();
        var encoded=CanonicalJson.encode(Map.of("schema_version","analysis-input-v1","anchor",anchor.view(),"anchor_fingerprint",anchor.fingerprint(),"frames",savedFrames),512*1024);
        when(db.queryForList(contains("SELECT i.*"),eq(inputId))).thenReturn(List.of(Map.of("input_id",inputId,"anchor_observation_id",observation,"anchor_record_version",1L,"camera_id","recording-01","manifest_text",encoded.payload(),"manifest_sha256",encoded.sha256())));
        when(search.mayReadCamera("recording-01")).thenReturn(true);when(evidence.anchor(observation,null)).thenReturn(anchor);when(evidence.frames(List.of(frame,secondId))).thenReturn(selected);
        when(files.read(secondId,second.reference())).thenThrow(new java.io.IOException("private test file unavailable"));
        assertEquals(digest,inputs.verify(inputId,frame).image().sha256());
        verify(files,never()).read(eq(secondId),anyString());verify(evidence).unchanged(anchor,selected);
        assertEquals("input_evidence_unavailable",assertThrows(InputRejected.class,()->inputs.verify(inputId,null)).code);
    }
    @Test void serviceTokenAndCurrentActorRevisionAreBothRequired(){
        var users=mock(UserService.class);var controller=new AnalysisInputController(inputs,users,"admin-token");
        assertEquals(401,controller.create("Bearer wrong","reader",1L,body("single_frame",digest)).block().getStatusCode().value());
        when(users.status("reader",1L)).thenReturn(Map.of("valid",false));
        assertEquals(401,controller.create("Bearer admin-token","reader",1L,body("single_frame",digest)).block().getStatusCode().value());
        verifyNoInteractions(evidence,files);
    }
    @Test void workerInputEndpointsRequireTheWorkerToken(){
        var service=mock(AnalysisInputs.class);var controller=new WorkerController(null,null,null,null,null,service,null,"worker-token");
        assertEquals(401,controller.inputManifest("Bearer admin-token",UUID.randomUUID()).block().getStatusCode().value());
        assertEquals(401,controller.inputImage(null,UUID.randomUUID(),frame).block().getStatusCode().value());
        verifyNoInteractions(service);
    }
    @Test void canonicalMapOrderingAndUnicodeStayCompatibleWithModuleSources(){
        assertEquals(CanonicalJson.encode(Map.of("b",List.of("画面",1),"a",true),1000),CanonicalJson.encode(new LinkedHashMap<>(Map.of("a",true,"b",List.of("画面",1))),1000));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.encode(Map.of("invalid",Double.NaN),1000));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.encode(Map.of("large","图".repeat(100)),50));
    }
}
