package cn.trial.videosearch.index;

import cn.trial.videosearch.http.SecureJsonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Hold one outbox row while indexing; replay is safe after a DB commit failure. */
@Service
public class OutboxIndexTransaction {
    private final JdbcTemplate db;
    private final SecureJsonClient es;
    private final String authorization;
    private final String indexName;
    private final ObjectMapper json = new ObjectMapper();

    public OutboxIndexTransaction(JdbcTemplate db, @Value("${VIDEO_ES_URL:}") String esUrl,
            @Value("${VIDEO_ES_CA:}") String esCa, @Value("${VIDEO_ES_INDEXER_USER:}") String user,
            @Value("${VIDEO_ES_INDEXER_PASSWORD:}") String password,
            @Value("${VIDEO_ES_INDEX:police-video-observations}") String indexName) {
        if (user.isBlank() || password.isBlank()) throw new IllegalArgumentException("indexer credentials must be configured");
        this.db = db;
        this.es = new SecureJsonClient(esUrl, esCa);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.indexName = SecureJsonClient.validateIndex(indexName);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public boolean processOne() {
        var rows = db.queryForList("""
                SELECT event_id, observation_id, record_version, deleted, document::text, attempts FROM index_outbox
                WHERE completed_at IS NULL AND next_attempt_at<=clock_timestamp()
                ORDER BY event_id FOR UPDATE SKIP LOCKED LIMIT 1
                """);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        long id = ((Number) row.get("event_id")).longValue();
        UUID observationId = (UUID) row.get("observation_id");
        long version = ((Number) row.get("record_version")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        try {
            Object parsed = json.readValue((String) row.get("document"), Object.class);
            if (!(parsed instanceof Map<?, ?> doc) || !observationId.toString().equals(doc.get("observation_id")) ||
                    !(doc.get("record_version") instanceof Number number) || number.longValue() != version ||
                    !row.get("deleted").equals(doc.get("deleted"))) throw new IllegalStateException("invalid index event");
            es.putVersioned(indexName, observationId, version, (Map<String, Object>) doc, authorization);
            db.update("UPDATE index_outbox SET completed_at=clock_timestamp() WHERE event_id=?", id);
        } catch (RuntimeException e) {
            int seconds = Math.min(300, 1 << Math.min(attempts + 1, 8));
            db.update("UPDATE index_outbox SET attempts=attempts+1, next_attempt_at=clock_timestamp()+(? * interval '1 second') WHERE event_id=?", seconds, id);
        }
        return true;
    }
}
