package cn.trial.videosearch.ingest;

import cn.trial.videosearch.attributes.ColorTerms;
import cn.trial.videosearch.attributes.UncertaintyTerms;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Save a frame, individual object records and immutable index events atomically. */
@Service
public class ObservationStore {
    public record VisibleObject(String objectType, Map<String, Object> attributes, String description,
                                List<Double> bboxXyxy, List<String> uncertainFields) {}
    public record Frame(UUID frameId, UUID taskId, String leaseOwner, long generation, String evidenceRef,
                        Long mediaOffsetMs, Instant sourceTime, String modelId, String promptVersion,
                        List<VisibleObject> objects) {}
    public record AnalysisResult(UUID jobId, String workerId, long generation, String modelId, String promptVersion,
                                 List<VisibleObject> objects) {}
    private static final Set<String> ATTRIBUTE_FIELDS = Set.of("upper_color", "lower_color", "vehicle_color", "carrying", "visible_action", "transport_type");
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public ObservationStore(JdbcTemplate db) { this.db = db; }

    @Transactional
    public int save(Frame frame) {
        validate(frame);
        if (!Integer.valueOf(0).equals(db.queryForObject("SELECT count(*) FROM capture_outbox WHERE frame_id=?", Integer.class, frame.frameId())))
            throw new IllegalStateException("target-bound frame requires module completion");
        var leases = db.queryForList("""
                SELECT s.camera_id, s.source_time_origin, s.source_type FROM processing_tasks t JOIN sources s ON s.source_id=t.source_id
                WHERE t.task_id=? AND t.state='active' AND t.lease_owner=? AND t.lease_generation=?
                  AND t.lease_expires_at>clock_timestamp() AND s.enabled FOR UPDATE OF t
                """, frame.taskId(), frame.leaseOwner(), frame.generation());
        if (leases.size() != 1) throw new IllegalStateException("lease expired or replaced");
        String cameraId = (String) leases.getFirst().get("camera_id");
        String origin = (String) leases.getFirst().get("source_time_origin");
        if (!origin.equals("verified") && frame.sourceTime() != null) throw new IllegalArgumentException("source clock is unverified");
        var incomingTime = frame.sourceTime() == null ? null : Timestamp.from(frame.sourceTime());
        int inserted = db.update("""
                INSERT INTO frames(frame_id, task_id, lease_generation, evidence_ref, media_offset_ms, source_time,
                                   source_time_origin, received_at, analysis_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), 'completed') ON CONFLICT (frame_id) DO NOTHING
                """, frame.frameId(), frame.taskId(), frame.generation(), frame.evidenceRef(), frame.mediaOffsetMs(), incomingTime, origin);
        if (inserted == 0) {
            Integer same = db.queryForObject("""
                    SELECT count(*) FROM frames WHERE frame_id=? AND task_id=? AND lease_generation=? AND evidence_ref=?
                      AND media_offset_ms IS NOT DISTINCT FROM ? AND source_time IS NOT DISTINCT FROM ? AND source_time_origin=?
                    """, Integer.class, frame.frameId(), frame.taskId(), frame.generation(), frame.evidenceRef(), frame.mediaOffsetMs(), incomingTime, origin);
            if (same == null || same != 1) throw new IllegalStateException("conflicting frame replay");
            Integer previousCount = db.queryForObject("SELECT count(*) FROM observations WHERE frame_id=?", Integer.class, frame.frameId());
            if (previousCount == null || previousCount != frame.objects().size()) throw new IllegalStateException("conflicting object count");
        }
        Timestamp received = db.queryForObject("SELECT received_at FROM frames WHERE frame_id=?", Timestamp.class, frame.frameId());
        if (received == null) throw new IllegalStateException("frame missing");
        int count = persistObjects(frame, cameraId, origin, (String) leases.getFirst().get("source_type"), received);
        int live = db.update("""
                UPDATE processing_tasks SET updated_at=clock_timestamp() WHERE task_id=? AND state='active' AND lease_owner=?
                  AND lease_generation=? AND lease_expires_at>clock_timestamp()
                """, frame.taskId(), frame.leaseOwner(), frame.generation());
        if (live != 1) throw new IllegalStateException("lease expired before commit");
        return count;
    }

    private int persistObjects(Frame frame, String cameraId, String origin, String sourceType, Timestamp received) {
        int count = 0;
        for (int ordinal = 0; ordinal < frame.objects().size(); ordinal++) {
            VisibleObject object = frame.objects().get(ordinal);
            Map<String, Object> attrs = normalizedAttributes(object.attributes());
            String attrsJson = json.writeValueAsString(attrs);
            String bboxJson = object.bboxXyxy() == null ? null : json.writeValueAsString(object.bboxXyxy());
            List<String> uncertain = normalizedUncertain(object.uncertainFields());
            String uncertainJson = json.writeValueAsString(uncertain);
            UUID observationId = UUID.nameUUIDFromBytes((frame.frameId() + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
            int created = db.update("""
                    INSERT INTO observations(observation_id, frame_id, object_ordinal, camera_id, target_type, attributes,
                                             bbox_xyxy, uncertain_fields, description, model_id, prompt_version)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?) ON CONFLICT (observation_id) DO NOTHING
                    """, observationId, frame.frameId(), ordinal, cameraId, object.objectType(), attrsJson,
                    bboxJson, uncertainJson, object.description(), frame.modelId(), frame.promptVersion());
            if (created == 0) {
                Integer same = db.queryForObject("""
                        SELECT count(*) FROM observations WHERE observation_id=? AND frame_id=? AND object_ordinal=? AND camera_id=?
                          AND target_type=? AND attributes=CAST(? AS jsonb) AND bbox_xyxy IS NOT DISTINCT FROM CAST(? AS jsonb)
                          AND uncertain_fields=CAST(? AS jsonb) AND description IS NOT DISTINCT FROM ?
                          AND model_id=? AND prompt_version=? AND record_version=1 AND NOT deleted
                        """, Integer.class, observationId, frame.frameId(), ordinal, cameraId, object.objectType(), attrsJson,
                        bboxJson, uncertainJson, object.description(), frame.modelId(), frame.promptVersion());
                if (same == null || same != 1) throw new IllegalStateException("conflicting observation replay");
            }
            var doc = new LinkedHashMap<String, Object>();
            doc.put("observation_id", observationId.toString());
            doc.put("frame_id", frame.frameId().toString());
            doc.put("camera_id", cameraId);
            doc.put("target_type", object.objectType());
            var detected = db.queryForList("SELECT target_id,track_id,class_name,confidence FROM detected_targets WHERE frame_id=? AND observation_ordinal=?", frame.frameId(), ordinal);
            if (!detected.isEmpty()) {
                var target = detected.getFirst();
                doc.put("target_id", target.get("target_id").toString());
                doc.put("track_id", target.get("track_id").toString());
                doc.put("detector_class", target.get("class_name"));
                doc.put("detector_confidence", target.get("confidence"));
                doc.put("association", "local_geometry_candidate");
            }
            doc.put("bbox_xyxy", object.bboxXyxy());
            doc.put("uncertain_fields", uncertain);
            doc.put("source_type", sourceType);
            doc.put("media_offset_ms", frame.mediaOffsetMs());
            doc.put("source_time_origin", origin);
            doc.put("source_time", frame.sourceTime() == null ? null : frame.sourceTime().toString());
            doc.put("received_at", received.toInstant().toString());
            // Preserve uncertain values in PostgreSQL for review, but do not turn them into exact search evidence.
            doc.putAll(indexedAttributes(attrs, uncertain));
            doc.put("description", object.description());
            doc.put("model_id", frame.modelId());
            doc.put("prompt_version", frame.promptVersion());
            doc.put("record_version", 1L);
            doc.put("deleted", false);
            String document = json.writeValueAsString(doc);
            db.update("""
                    INSERT INTO index_outbox(observation_id, record_version, deleted, document)
                    VALUES (?, 1, false, CAST(? AS jsonb)) ON CONFLICT (observation_id, record_version) DO NOTHING
                    """, observationId, document);
            count++;
        }
        Integer existingObjects = db.queryForObject("SELECT count(*) FROM observations WHERE frame_id=?", Integer.class, frame.frameId());
        if (existingObjects == null || existingObjects != frame.objects().size()) throw new IllegalStateException("conflicting object count");
        return count;
    }

    /** Complete a separately leased analysis job; original video lease may already have expired. */
    @Transactional
    public int complete(AnalysisResult result) {
        return completeInternal(result, false);
    }

    @Transactional
    public int completeBound(AnalysisResult result) {
        return completeInternal(result, true);
    }

    private int completeInternal(AnalysisResult result, boolean targetBound) {
        if (result == null || result.jobId() == null || result.workerId() == null ||
                !result.workerId().matches("[A-Za-z0-9_.-]{1,100}") || result.generation() < 1)
            throw new IllegalArgumentException("invalid analysis result");
        var rows = db.queryForList("""
                SELECT j.state, f.frame_id, f.task_id, f.lease_generation, f.evidence_ref, f.media_offset_ms,
                       f.source_time, f.source_time_origin, f.received_at, s.camera_id, s.source_type
                FROM analysis_jobs j JOIN frames f ON f.frame_id=j.frame_id
                JOIN processing_tasks t ON t.task_id=f.task_id JOIN sources s ON s.source_id=t.source_id
                WHERE j.job_id=? AND j.lease_owner=? AND j.lease_generation=?
                  AND ((j.state='active' AND j.lease_expires_at>clock_timestamp()) OR j.state='completed')
                FOR UPDATE OF j,f
                """, result.jobId(), result.workerId(), result.generation());
        if (rows.size() != 1) throw new IllegalStateException("analysis lease expired or replaced");
        var row = rows.getFirst();
        Integer planned = db.queryForObject("SELECT count(*) FROM capture_outbox WHERE frame_id=?", Integer.class, row.get("frame_id"));
        if (!targetBound && planned != null && planned > 0) throw new IllegalStateException("target-bound completion required");
        var sourceTime = (Timestamp) row.get("source_time");
        var offset = (Number) row.get("media_offset_ms");
        var frame = new Frame((UUID) row.get("frame_id"), (UUID) row.get("task_id"), result.workerId(),
                ((Number) row.get("lease_generation")).longValue(), (String) row.get("evidence_ref"),
                offset == null ? null : offset.longValue(), sourceTime == null ? null : sourceTime.toInstant(),
                result.modelId(), result.promptVersion(), result.objects());
        validate(frame);
        boolean completed = "completed".equals(row.get("state"));
        if (completed) {
            Integer previousCount = db.queryForObject("SELECT count(*) FROM observations WHERE frame_id=?", Integer.class, frame.frameId());
            if (previousCount == null || previousCount != frame.objects().size()) throw new IllegalStateException("conflicting analysis replay");
        }
        int count = persistObjects(frame, (String) row.get("camera_id"), (String) row.get("source_time_origin"),
                (String) row.get("source_type"), (Timestamp) row.get("received_at"));
        if (!completed) {
            int live = db.update("""
                    UPDATE analysis_jobs SET state='completed', updated_at=clock_timestamp() WHERE job_id=? AND state='active'
                      AND lease_owner=? AND lease_generation=? AND lease_expires_at>clock_timestamp()
                    """, result.jobId(), result.workerId(), result.generation());
            if (live != 1) throw new IllegalStateException("analysis lease expired before commit");
            db.update("UPDATE frames SET analysis_state='completed' WHERE frame_id=?", frame.frameId());
        }
        return count;
    }

    /** A persistent indexed tombstone prevents delayed older events from resurrecting deleted records. */
    @Transactional
    @SuppressWarnings("unchecked")
    public long tombstone(UUID observationId, long expectedVersion) {
        if (observationId == null || expectedVersion < 1) throw new IllegalArgumentException("invalid observation version");
        var rows = db.queryForList("SELECT record_version, deleted FROM observations WHERE observation_id=? FOR UPDATE", observationId);
        if (rows.size() != 1 || ((Number) rows.getFirst().get("record_version")).longValue() != expectedVersion ||
                Boolean.TRUE.equals(rows.getFirst().get("deleted"))) throw new IllegalStateException("observation version conflict");
        String prior = db.queryForObject("SELECT document::text FROM index_outbox WHERE observation_id=? AND record_version=?",
                String.class, observationId, expectedVersion);
        if (prior == null) throw new IllegalStateException("index history missing");
        Object parsed = json.readValue(prior, Object.class);
        if (!(parsed instanceof Map<?, ?>)) throw new IllegalStateException("index history invalid");
        var doc = new LinkedHashMap<>((Map<String, Object>) parsed);
        long next = Math.addExact(expectedVersion, 1);
        doc.put("record_version", next);
        doc.put("deleted", true);
        db.update("UPDATE observations SET deleted=true, record_version=?, updated_at=clock_timestamp() WHERE observation_id=?", next, observationId);
        db.update("INSERT INTO index_outbox(observation_id, record_version, deleted, document) VALUES (?, ?, true, CAST(? AS jsonb))",
                observationId, next, json.writeValueAsString(doc));
        return next;
    }

    private static void validate(Frame frame) {
        if (frame == null || frame.frameId() == null || frame.taskId() == null || frame.leaseOwner() == null ||
                !frame.leaseOwner().matches("[A-Za-z0-9_.-]{1,100}") || frame.generation() <= 0 ||
                frame.evidenceRef() == null || !frame.evidenceRef().matches("evidence:[A-Za-z0-9_-]{1,100}") ||
                (frame.mediaOffsetMs() != null && frame.mediaOffsetMs() < 0) || !shortText(frame.modelId(), 200) ||
                !shortText(frame.promptVersion(), 100) || frame.objects() == null || frame.objects().size() > 100)
            throw new IllegalArgumentException("invalid frame");
        for (VisibleObject object : frame.objects()) {
            validateObject(object);
        }
    }

    static void validateObject(VisibleObject object) {
        if (object == null || object.objectType() == null || !Set.of("person", "vehicle").contains(object.objectType()) ||
                (object.description() != null && object.description().length() > 500)) throw new IllegalArgumentException("invalid object");
        normalizedAttributes(object.attributes());
        if ("person".equals(object.objectType()) && object.attributes().get("vehicle_color") != null)
            throw new IllegalArgumentException("vehicle color cannot describe a person");
        validateBoundingBox(object.bboxXyxy());
        normalizedUncertain(object.uncertainFields());
    }

    static void validateBoundingBox(List<Double> box) {
        if (box == null) return;
        if (box.size() != 4 || box.stream().anyMatch(x -> x == null || !Double.isFinite(x) || x < 0 || x > 100_000)
                || box.get(2) <= box.get(0) || box.get(3) <= box.get(1))
            throw new IllegalArgumentException("invalid bounding box");
    }

    static List<String> normalizedUncertain(List<String> fields) {
        if (fields == null) return List.of();
        if (fields.size() > 20) throw new IllegalArgumentException("invalid uncertain fields");
        var output = new ArrayList<String>();
        for (String field : fields) {
            if (!shortText(field, 100)) throw new IllegalArgumentException("invalid uncertain field");
            String canonical = switch (field.strip()) {
                case "upper_clothing_color" -> "upper_color";
                case "lower_clothing_color" -> "lower_color";
                default -> field.strip();
            };
            if (!output.contains(canonical)) output.add(canonical);
        }
        return output;
    }

    public static Map<String, Object> indexedAttributes(Map<String, Object> attrs, List<String> uncertain) {
        var indexed = new LinkedHashMap<String, Object>();
        var suppressed = UncertaintyTerms.suppressedAttributes(uncertain);
        attrs.forEach((key, value) -> {
            if (!suppressed.contains(key)) indexed.put(key,
                    key.endsWith("_color") && value instanceof String s ? ColorTerms.canonical(s) : value);
        });
        return indexed;
    }

    private static Map<String, Object> normalizedAttributes(Map<String, Object> input) {
        if (input == null || !ATTRIBUTE_FIELDS.containsAll(input.keySet())) throw new IllegalArgumentException("invalid attributes");
        var output = new LinkedHashMap<String, Object>();
        for (var entry : input.entrySet()) {
            if (entry.getValue() == null) continue;
            if (entry.getKey().equals("carrying")) {
                if (!(entry.getValue() instanceof List<?> list) || list.size() > 12) throw new IllegalArgumentException("invalid carrying");
                var items = new ArrayList<String>();
                for (Object value : list) {
                    if (!(value instanceof String s) || !shortText(s, 100)) throw new IllegalArgumentException("invalid carrying");
                    items.add(s.strip());
                }
                output.put(entry.getKey(), items);
            } else if (entry.getValue() instanceof String s && shortText(s, 80)) {
                output.put(entry.getKey(), s.strip());
            } else throw new IllegalArgumentException("invalid attribute");
        }
        return output;
    }

    private static boolean shortText(String s, int max) { return s != null && !s.isBlank() && s.length() <= max; }
}
