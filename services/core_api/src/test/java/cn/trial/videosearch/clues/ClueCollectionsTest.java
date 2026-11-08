package cn.trial.videosearch.clues;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import cn.trial.videosearch.evidence.ObservationDetails;
import cn.trial.videosearch.search.SearchOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClueCollectionsTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final SearchOrchestrator search=mock(SearchOrchestrator.class);
    final ObservationDetails details=mock(ObservationDetails.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final ClueCollections service=new ClueCollections(db,search,details,transactions);
    final UUID collection=UUID.randomUUID(),observation=UUID.randomUUID();
    void collection(String state,long revision) {
        when(db.queryForList(startsWith("SELECT * FROM clue_collections"),eq(collection)))
            .thenReturn(List.of(new LinkedHashMap<>(Map.of("collection_id",collection,"state",state,"revision",revision,"create_fingerprint","private"))));
        when(db.queryForList(startsWith("SELECT DISTINCT o.camera_id"),eq(collection))).thenReturn(List.of(Map.of("camera_id","recording-01")));
        when(search.mayReadCamera("recording-01")).thenReturn(true);
    }
    @Test void timelineUsesOnlyVerifiedSourceClocksAndNeverReceiptOrderAsTrajectoryTime() {
        assertFalse(ClueCollections.verifiedTimeline(List.of()));
        Map<String,Object> valid=Map.of("available",true,"source_time_origin","verified","source_time","2026-01-01T00:00:00Z");
        assertTrue(ClueCollections.verifiedTimeline(List.of(valid)));
        assertFalse(ClueCollections.verifiedTimeline(List.of(valid,Map.of("available",true,"source_time_origin","simulated","received_at","2026-01-01T00:00:01Z"))));
        assertFalse(ClueCollections.verifiedTimeline(List.of(Map.of("available",false,"source_time_origin","verified","source_time","2026-01-01T00:00:00Z"))));
    }
    @Test void csvEscapesFormulasWhitespaceQuotesAndMultilineNotes() {
        assertEquals("\"'=SUM(A1)\"",ClueCollections.csvCell("=SUM(A1)"));
        assertEquals("\"'  +2\"",ClueCollections.csvCell("  +2"));
        assertEquals("\"'@SUM(A1)\"",ClueCollections.csvCell("@SUM(A1)"));
        assertEquals("\"a\"\"b\n第二行\"",ClueCollections.csvCell("a\"b\n第二行"));
        assertEquals("\"\"",ClueCollections.csvCell(null));
    }
    @Test void reviewTextIsBoundedWithoutDestroyingLegitimateNewlines() {
        assertEquals("第一行\n第二行",ClueCollections.text("  第一行\n第二行  ",30,true));
        assertThrows(IllegalArgumentException.class,()->ClueCollections.text(" ",30,true));
        assertThrows(IllegalArgumentException.class,()->ClueCollections.text("a\u0000b",30,false));
        assertThrows(IllegalArgumentException.class,()->ClueCollections.text("x".repeat(2001),2000,false));
    }
    @Test void staleCollectionCannotEnqueueOrModifyAnyObservation() {
        collection("open",3);
        var error=assertThrows(AdminService.Rejected.class,()->service.add(collection,observation,new ClueCollections.Add(2,1),"reader"));
        assertEquals("collection_revision_conflict",error.code);verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(details);
    }
    @Test void archivedCollectionRejectsEditsToItems() {
        collection("closed",3);
        var error=assertThrows(AdminService.Rejected.class,()->service.add(collection,observation,new ClueCollections.Add(3,1),"reader"));
        assertEquals("collection_closed",error.code);verifyNoInteractions(details);
    }
    @Test void hiddenCameraPreventsEvenCollectionMetadataFromBeingReturned() {
        collection("open",1);when(search.mayReadCamera("recording-01")).thenReturn(false);
        var error=assertThrows(AdminService.Rejected.class,()->service.read(collection));assertEquals(404,error.status);
        verifyNoInteractions(details);
    }
    @Test void updatedObservationCannotBeAddedUsingAnOldSearchSnapshot() {
        collection("open",1);
        when(db.queryForList(startsWith("SELECT observation_id,camera_id"),eq(observation))).thenReturn(List.of(Map.of("observation_id",observation,"camera_id","recording-01","record_version",2L)));
        var error=assertThrows(AdminService.Rejected.class,()->service.add(collection,observation,new ClueCollections.Add(1,1),"reader"));assertEquals("observation_revision_conflict",error.code);
        verify(db,never()).update(startsWith("INSERT INTO clue_collection_items"),any(Object[].class));
    }
    @Test void reviewMustBindTheActualCurrentJpegBeforeWritingAnOpinion() throws Exception {
        collection("open",1);
        when(db.queryForList(startsWith("SELECT observation_id,camera_id"),eq(observation))).thenReturn(List.of(Map.of("observation_id",observation,"camera_id","recording-01","record_version",1L)));
        when(db.queryForList(startsWith("SELECT review_state,review_note"),eq(collection),eq(observation))).thenReturn(List.of(Map.of("review_state","pending","review_note","")));
        when(details.read(observation)).thenReturn(Optional.of(Map.of("evidence",Map.of("available",true,"sha256","a".repeat(64),"capture_integrity","matched"))));
        var error=assertThrows(AdminService.Rejected.class,()->service.review(collection,observation,new ClueCollections.Review(1,1,"b".repeat(64),"retained","核验意见"),"reader"));
        assertEquals("review_evidence_changed",error.code);
        verify(db,never()).update(startsWith("UPDATE clue_collection_items"),any(Object[].class));
        verifyNoInteractions(transactions);
    }
    @Test void evidenceIoFinishesBeforeOpeningTheWriteTransactionAndVersionIsRechecked() throws Exception {
        collection("open",1);
        when(details.read(observation)).thenAnswer(invocation->{
            verifyNoInteractions(transactions);
            return Optional.of(Map.of("evidence",Map.of("available",true,"sha256","a".repeat(64),"capture_integrity","matched")));
        });
        var transaction=new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        when(db.queryForList(startsWith("SELECT observation_id,camera_id"),eq(observation)))
            .thenReturn(List.of(Map.of("observation_id",observation,"camera_id","recording-01","record_version",2L)));
        var error=assertThrows(AdminService.Rejected.class,()->service.review(collection,observation,
            new ClueCollections.Review(1,1,"a".repeat(64),"retained","核验意见"),"reader"));
        assertEquals("observation_revision_conflict",error.code);
        verify(transactions).rollback(transaction);
        verify(db,never()).update(startsWith("UPDATE clue_collection_items"),any(Object[].class));
    }
    @Test void staleCollectionAfterEvidenceReadCannotCommitAReview() throws Exception {
        collection("open",1);
        when(details.read(observation)).thenAnswer(invocation->{
            collection("open",2);
            return Optional.of(Map.of("evidence",Map.of("available",true,"sha256","a".repeat(64),"capture_integrity","matched")));
        });
        var transaction=new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var error=assertThrows(AdminService.Rejected.class,()->service.review(collection,observation,
            new ClueCollections.Review(1,1,"a".repeat(64),"retained","核验意见"),"reader"));
        assertEquals("collection_revision_conflict",error.code);
        verify(transactions).rollback(transaction);
        verify(db,never()).update(startsWith("UPDATE clue_collection_items"),any(Object[].class));
    }
    @Test void repeatedCreateRequestWithDifferentIntentDoesNotOverwriteTheWorkspace() {
        when(db.queryForList(startsWith("SELECT create_fingerprint"),eq(collection))).thenReturn(List.of(Map.of("create_fingerprint","different")));
        var error=assertThrows(AdminService.Rejected.class,()->service.create(new ClueCollections.Create(collection,"清单",""),"reader"));
        assertEquals("collection_request_conflict",error.code);verify(db,never()).update(startsWith("INSERT INTO clue_collections"),any(Object[].class));
    }
    @Test void selfLinkIsInvalidBeforeAnyDatabaseWrites() {
        assertThrows(IllegalArgumentException.class,()->service.link(collection,observation,observation,new ClueCollections.Link(1,"similar","pending"),"reader"));verifyNoInteractions(db);
    }
    @Test void serviceTokenAloneOrAnObsoleteAccountRevisionCannotWriteBusinessReviews() {
        var users=mock(UserService.class);var controller=new ClueController(service,users,"management-token");
        when(users.status("reader",1)).thenReturn(Map.of("valid",false));
        assertEquals(401,controller.read("Bearer wrong","reader",1L,collection).block().getStatusCode().value());
        assertEquals(401,controller.read("Bearer management-token","reader",1L,collection).block().getStatusCode().value());
        verifyNoInteractions(db);
    }
}
