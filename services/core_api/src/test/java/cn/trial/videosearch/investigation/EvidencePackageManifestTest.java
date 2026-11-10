package cn.trial.videosearch.investigation;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.clues.ClueCollections;
import cn.trial.videosearch.evidence.ObservationDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Nonvisual source/version protocol fixtures, without media generation or inference. */
class EvidencePackageManifestTest {
    private final UUID id=UUID.randomUUID(),observation=UUID.randomUUID(),other=UUID.randomUUID(),frame=UUID.randomUUID();
    private final ClueCollections clues=mock(ClueCollections.class);
    private final ObservationDetails details=mock(ObservationDetails.class);
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final EvidencePackageManifest packages=new EvidencePackageManifest(clues,details,db);
    private Map<String,Object> collection(){return Map.of("collection",Map.of("collection_id",id.toString(),"revision",1L),"nodes",List.of(node(observation),node(other)),"links",List.of());}
    private Map<String,Object> node(UUID observation){return new LinkedHashMap<>(Map.of("observation_id",observation.toString(),"frame_id",frame.toString(),"record_version",1L,"available",true,"review_state","pending","description","非视觉协议字段"));}
    private void seed()throws Exception{
        when(clues.read(id)).thenReturn(collection());
        when(details.read(observation)).thenReturn(Optional.of(Map.of("observation_id",observation.toString(),"frame_id",frame.toString(),"record_version",1L,"evidence",Map.of("available",true,"capture_integrity","matched","bytes",10,"sha256","a".repeat(64)))));
    }
    @Test void deduplicatesImagesButKeepsAllBusinessReviewRecords()throws Exception{
        seed();var result=packages.prepare(id,1,1,"reader");var manifest=new ObjectMapper().readValue((String)result.get("manifest_payload"),Map.class);
        assertEquals(1,((List<?>)manifest.get("images")).size());assertEquals(2,((List<?>)((Map<?,?>)manifest.get("collection")).get("nodes")).size());
        verify(details).read(observation);verifyNoMoreInteractions(details);
    }
    @ParameterizedTest @ValueSource(strings={"version","review","description","same_frame_other_target"})
    @SuppressWarnings("unchecked") void sourceChangesWithoutCollectionRevisionCannotReturnAPackage(String field)throws Exception{
        seed();var initial=collection();var changed=collection();var nodes=(List<Map<String,Object>>)changed.get("nodes");
        switch(field){case "version"->nodes.getFirst().put("record_version",2L);case "review"->nodes.getFirst().put("review_state","excluded");case "description"->nodes.getFirst().put("description","来源已修改");default->nodes.getLast().put("review_state","retained");}
        when(clues.read(id)).thenReturn(initial,changed);
        assertEquals("package_source_changed",assertThrows(AdminService.Rejected.class,()->packages.prepare(id,1,1,"reader")).code);
        verifyNoInteractions(db);
    }
    @Test void collectionRevisionConflictKeepsItsExistingErrorContract()throws Exception{
        seed();when(clues.read(id)).thenReturn(collection(),Map.of("collection",Map.of("revision",2L)));
        assertEquals("collection_revision_conflict",assertThrows(AdminService.Rejected.class,()->packages.prepare(id,1,1,"reader")).code);verifyNoInteractions(db);
    }
    @Test void emptyCollectionStillHasOneMetadataPartWithoutReadingImages()throws Exception{
        when(clues.read(id)).thenReturn(Map.of("collection",Map.of("collection_id",id.toString(),"revision",1L),"nodes",List.of(),"links",List.of()));
        var result=packages.prepare(id,1,1,"reader");assertEquals(1,result.get("parts"));verifyNoInteractions(details);
        assertThrows(IllegalArgumentException.class,()->packages.prepare(id,1,14,"reader"));
    }
}
