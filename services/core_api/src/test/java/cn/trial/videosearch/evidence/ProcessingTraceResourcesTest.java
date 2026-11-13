package cn.trial.videosearch.evidence;

import cn.trial.videosearch.http.SecureJsonClient;
import cn.trial.videosearch.index.ObservationIndexReader;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Nonvisual projection protocol fixtures, not recognition accuracy evidence. */
class ProcessingTraceResourcesTest {
    private final UUID frame=UUID.randomUUID();
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObservationIndexReader index=mock(ObservationIndexReader.class);
    private final ProcessingTraceResources resources=new ProcessingTraceResources(db,index);
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private Map<String,Object> projection(Map<String,Object> row){return Map.of("observation_id",row.get("observation_id").toString(),"record_version",row.get("record_version"),"deleted",false,"description","协议字段");}
    private void seed(int count){
        for(int n=0;n<count;n++){
            var row=new LinkedHashMap<String,Object>();row.put("observation_id",UUID.randomUUID());row.put("record_version",1L);row.put("deleted",false);
            row.put("document",CanonicalJson.encode(projection(row),4096).payload());rows.add(row);
        }
        when(db.queryForList(startsWith("SELECT o.observation_id"),eq(frame))).thenReturn(rows);
        when(db.queryForList(startsWith("SELECT t.source_id"),eq(frame),eq(frame))).thenReturn(List.of(Map.of("source_id",UUID.randomUUID(),"source_ref","private-reference")));
        when(index.read(anyList())).thenAnswer(call->{
            List<UUID> ids=call.getArgument(0);
            return Map.of("docs",rows.stream().filter(row->ids.contains(row.get("observation_id"))).map(this::document).toList());
        });
    }
    private Map<String,Object> document(Map<String,Object> row){return new LinkedHashMap<>(Map.of("_id",row.get("observation_id").toString(),"found",true,"_version",1,"_source",projection(row)));}
    private Map<String,Object> indexResult(){return resources.verify(frame).getFirst();}
    @Test void readsBoundedBatchesAndRequiresAllCurrentVersionsAndContent(){
        seed(51);var result=indexResult();assertEquals("matched",result.get("state"));
        assertEquals(51,((Map<?,?>)result.get("details")).get("matched"));verify(index,times(3)).read(argThat(ids->!ids.isEmpty()&&ids.size()<=25));
    }
    @ParameterizedTest @ValueSource(strings={"missing","version","content","transport","duplicate","foreign","item_error"})
    void missingChangedAndUnavailableAreNeverReportedAsMatching(String scenario){
        seed(2);var docs=new ArrayList<Map<String,Object>>(rows.stream().map(this::document).toList());String state;
        switch(scenario){
            case "missing"->{docs.set(0,Map.of("_id",rows.getFirst().get("observation_id").toString(),"found",false));state="missing";}
            case "version"->{docs.getFirst().put("_version",2);state="changed";}
            case "content"->{docs.getFirst().put("_source",Map.of("description","其他内容"));state="changed";}
            case "transport"->{state="unavailable";}
            case "duplicate"->{docs.set(1,docs.getFirst());state="unavailable";}
            case "foreign"->{docs.getFirst().put("_id",UUID.randomUUID().toString());state="unavailable";}
            default->{docs.getFirst().put("error",Map.of("type","permission_denied"));state="unavailable";}
        }
        if(scenario.equals("transport"))when(index.read(anyList())).thenThrow(new SecureJsonClient.UpstreamFailure(503));
        else when(index.read(anyList())).thenReturn(Map.of("docs",docs));
        assertEquals(state,indexResult().get("state"));
    }
    @Test void overLimitDoesNotReadAnUnboundedIndex(){seed(101);assertEquals("not_checked",indexResult().get("state"));verifyNoInteractions(index);}
    @Test void sourceChangesDuringResourceReadsRejectTheWholeSnapshot(){
        seed(1);when(db.queryForList(startsWith("SELECT t.source_id"),eq(frame),eq(frame))).thenReturn(List.of(Map.of("source_ref","before")),List.of(Map.of("source_ref","after")));
        assertEquals("trace_source_changed",assertThrows(InputRejected.class,()->resources.verify(frame)).code);
    }
}
