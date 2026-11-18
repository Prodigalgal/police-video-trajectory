package cn.trial.videosearch.ingress;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** PostgreSQL-backed ADR023 registry, composite channel mapping, and leased media session coordinator. */
@Service
public class VideoPlatformService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long LOCK_KEY = 918273701L;
    private static final int MAX_PLATFORMS = 32, MAX_CHANNELS = 200, MAX_ACTIVE_SESSIONS = 32;

    public record PlatformUpsert(
        UUID requestId,
        long expectedRevision,
        String adapterKind,
        String displayName,
        String endpointRef,
        String mediaNode,
        String clockState,
        Long clockOffsetMs,
        Boolean enabled
    ) {}

    public record ChannelMappingUpsert(
        UUID requestId,
        long expectedRevision,
        String deviceId,
        String channelId,
        String cameraId,
        String channelName,
        String streamProfile,
        Boolean clockVerified,
        Long clockOffsetMs,
        Boolean active
    ) {}

    public record AcquireSessionRequest(
        UUID requestId,
        String platformId,
        String deviceId,
        String channelId,
        String sessionKind,
        String streamProfile,
        String workerId,
        int leaseSeconds,
        Instant sourceStartTime,
        Instant sourceEndTime
    ) {}

    public record RenewSessionRequest(String workerId, UUID leaseToken, int leaseSeconds) {}
    public record ReleaseSessionRequest(String workerId, UUID leaseToken, String reason) {}

    private final JdbcTemplate db;
    private final SearchOrchestrator scope;
    private final Map<String, VideoPlatformAdapter> adapters;
    private final TransactionTemplate tx;

    public VideoPlatformService(JdbcTemplate db, SearchOrchestrator scope,
                                List<VideoPlatformAdapter> adapterList, PlatformTransactionManager manager) {
        this.db = db;
        this.scope = scope;
        this.adapters = adapterList.stream().collect(Collectors.toUnmodifiableMap(VideoPlatformAdapter::adapterKind, Function.identity()));
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }

    private VideoPlatformAdapter adapter(String kind) {
        var found = adapters.get(kind);
        if (found == null) throw new IllegalArgumentException("unsupported platform adapter");
        return found;
    }

    private static String safeText(String raw, int minLen, int maxLen, String code) {
        if (raw == null || raw.isBlank() || raw.length() > maxLen || raw.chars().anyMatch(Character::isISOControl)) {
            throw new AdminService.Rejected(400, code);
        }
        String trimmed = raw.strip();
        if (trimmed.length() < minLen || trimmed.length() > maxLen) throw new AdminService.Rejected(400, code);
        return trimmed;
    }

    private static String safeEndpointRef(String raw) {
        String trimmed = safeText(raw, 2, 120, "invalid_platform_endpoint");
        // Reject raw URLs, embedded credentials, query strings, or scheme prefixes.
        if (trimmed.contains("://") || trimmed.contains("@") || trimmed.contains("?") || trimmed.contains("#")
                || !trimmed.matches("^[A-Za-z0-9][A-Za-z0-9_.:/-]{1,119}$")) {
            throw new AdminService.Rejected(400, "invalid_platform_endpoint");
        }
        return trimmed;
    }

    public Map<String, Object> capabilities() {
        return Map.of(
            "schema_version", "platform-ingress-v1",
            "adapters", adapters.values().stream()
                .sorted(Comparator.comparing(VideoPlatformAdapter::adapterKind))
                .map(VideoPlatformAdapter::capabilities)
                .toList(),
            "interoperability_verified_default", false,
            "allows_raw_url_from_client", false
        );
    }

    public Map<String, Object> listPlatforms() {
        var rows = db.queryForList("""
            SELECT p.*,
              (SELECT count(*) FROM platform_camera_channels c WHERE c.platform_id=p.platform_id AND c.active) AS active_channels
            FROM video_platforms p ORDER BY p.platform_id
            """);
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var spec = toPlatformSpec(row);
            var item = platformView(spec);
            item.put("active_channels", ((Number) row.get("active_channels")).longValue());
            item.put("health", adapter(spec.adapterKind()).health(spec));
            items.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", "platform-ingress-v1");
        result.put("adapters", capabilities().get("adapters"));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> upsertPlatform(String platformId, PlatformUpsert req, String actor) {
        if (platformId == null || !platformId.matches("^[a-z0-9][a-z0-9_-]{0,79}$")
                || req == null || req.requestId() == null || req.expectedRevision() < 0
                || req.enabled() == null) {
            throw new IllegalArgumentException("invalid platform upsert request");
        }
        var impl = adapter(req.adapterKind());
        String displayName = safeText(req.displayName(), 1, 200, "invalid_platform_request");
        String endpointRef = safeEndpointRef(req.endpointRef());
        String mediaNode = req.mediaNode() == null ? "" : req.mediaNode().strip();
        if (mediaNode.length() > 200 || mediaNode.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid media node");
        }
        String clockState = req.clockState() == null ? "unverified" : req.clockState();
        if (!Set.of("unverified", "verified").contains(clockState)) throw new IllegalArgumentException("invalid clock state");
        if (!"verified".equals(clockState) && req.clockOffsetMs() != null) {
            throw new AdminService.Rejected(400, "platform_clock_unverified");
        }

        var canonicalReq = new LinkedHashMap<String, Object>();
        canonicalReq.put("platform_id", platformId);
        canonicalReq.put("expected_revision", req.expectedRevision());
        canonicalReq.put("adapter_kind", impl.adapterKind());
        canonicalReq.put("display_name", displayName);
        canonicalReq.put("endpoint_ref", endpointRef);
        canonicalReq.put("media_node", mediaNode);
        canonicalReq.put("clock_state", clockState);
        canonicalReq.put("clock_offset_ms", req.clockOffsetMs());
        canonicalReq.put("enabled", req.enabled());
        String reqSha = CanonicalJson.encode(canonicalReq, 16384).sha256();

        return tx.execute(status -> {
            db.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
            var prior = checkCommandReceipt(req.requestId(), actor, "platform.upsert", reqSha);
            if (prior != null) return prior;

            var existing = db.queryForList("SELECT * FROM video_platforms WHERE platform_id=? FOR UPDATE", platformId);
            if (existing.isEmpty()) {
                if (req.expectedRevision() != 0) throw new AdminService.Rejected(409, "platform_revision_conflict");
                Long count = db.queryForObject("SELECT count(*) FROM video_platforms", Long.class);
                if (count != null && count >= MAX_PLATFORMS) throw new AdminService.Rejected(409, "platform_registry_full");
                db.update("""
                    INSERT INTO video_platforms(platform_id,adapter_kind,display_name,endpoint_ref,media_node,
                        clock_state,clock_offset_ms,interoperability_verified,enabled,revision,updated_by)
                    VALUES(?,?,?,?,?,?,?,false,?,1,?)
                    """, platformId, impl.adapterKind(), displayName, endpointRef, mediaNode,
                    clockState, req.clockOffsetMs(), req.enabled(), actor);
            } else {
                long currentRev = ((Number) existing.getFirst().get("revision")).longValue();
                if (currentRev != req.expectedRevision()) throw new AdminService.Rejected(409, "platform_revision_conflict");
                db.update("""
                    UPDATE video_platforms SET adapter_kind=?,display_name=?,endpoint_ref=?,media_node=?,
                        clock_state=?,clock_offset_ms=?,enabled=?,revision=revision+1,updated_by=?,updated_at=clock_timestamp()
                    WHERE platform_id=?
                    """, impl.adapterKind(), displayName, endpointRef, mediaNode,
                    clockState, req.clockOffsetMs(), req.enabled(), actor, platformId);
            }

            var saved = toPlatformSpec(db.queryForList("SELECT * FROM video_platforms WHERE platform_id=?", platformId).getFirst());
            var view = platformView(saved);
            view.put("health", impl.health(saved));
            var response = Map.<String, Object>of("platform", view, "request_id", req.requestId().toString());
            saveCommandReceipt(req.requestId(), "platform.upsert", actor, reqSha, response);
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'platform.upsert',?,?)",
                actor, CanonicalJson.encode(Map.of("platform_id", platformId, "revision", saved.revision()), 4096).payload(), req.requestId());
            return response;
        });
    }

    public Map<String, Object> listChannels(String platformId) {
        if (platformId == null || !platformId.matches("^[a-z0-9][a-z0-9_-]{0,79}$")) {
            throw new IllegalArgumentException("invalid platform id");
        }
        if (db.queryForList("SELECT 1 FROM video_platforms WHERE platform_id=?", platformId).isEmpty()) {
            throw new AdminService.Rejected(404, "platform_unavailable");
        }
        var allowed = scope.readableCameras().stream().sorted().toList();
        if (allowed.isEmpty()) return Map.of("platform_id", platformId, "items", List.of());
        var args = new ArrayList<Object>();
        args.add(platformId);
        args.addAll(allowed);
        String in = String.join(",", Collections.nCopies(allowed.size(), "?"));
        var rows = db.queryForList("""
            SELECT c.*, cam.display_name AS camera_display_name
            FROM platform_camera_channels c JOIN cameras cam ON cam.camera_id=c.camera_id
            WHERE c.platform_id=? AND c.camera_id IN (""" + in + ") ORDER BY c.camera_id,c.device_id,c.channel_id",
            args.toArray());
        return Map.of(
            "platform_id", platformId,
            "items", rows.stream().map(this::channelView).toList()
        );
    }

    public Map<String, Object> upsertChannelMapping(String platformId, ChannelMappingUpsert req, String actor) {
        if (platformId == null || !platformId.matches("^[a-z0-9][a-z0-9_-]{0,79}$")
                || req == null || req.requestId() == null || req.expectedRevision() < 0
                || req.deviceId() == null || !req.deviceId().matches("^[A-Za-z0-9_.-]{1,64}$")
                || req.channelId() == null || !req.channelId().matches("^[A-Za-z0-9_.-]{1,64}$")
                || req.cameraId() == null || !scope.mayReadCamera(req.cameraId())
                || req.active() == null) {
            throw new IllegalArgumentException("invalid channel mapping request");
        }
        String profile = req.streamProfile() == null ? "unknown" : req.streamProfile();
        if (!Set.of("main", "sub", "unknown").contains(profile)) throw new IllegalArgumentException("invalid stream profile");
        boolean clockVerified = Boolean.TRUE.equals(req.clockVerified());
        if (!clockVerified && req.clockOffsetMs() != null) {
            throw new AdminService.Rejected(400, "platform_clock_unverified");
        }
        String channelName = req.channelName() == null ? "" : req.channelName().strip();
        if (channelName.length() > 200 || channelName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid channel name");
        }

        var canonicalReq = new LinkedHashMap<String, Object>();
        canonicalReq.put("platform_id", platformId);
        canonicalReq.put("expected_revision", req.expectedRevision());
        canonicalReq.put("device_id", req.deviceId());
        canonicalReq.put("channel_id", req.channelId());
        canonicalReq.put("camera_id", req.cameraId());
        canonicalReq.put("channel_name", channelName);
        canonicalReq.put("stream_profile", profile);
        canonicalReq.put("clock_verified", clockVerified);
        canonicalReq.put("clock_offset_ms", req.clockOffsetMs());
        canonicalReq.put("active", req.active());
        String reqSha = CanonicalJson.encode(canonicalReq, 16384).sha256();

        return tx.execute(status -> {
            db.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
            var prior = checkCommandReceipt(req.requestId(), actor, "platform.channel.upsert", reqSha);
            if (prior != null) return prior;

            if (db.queryForList("SELECT 1 FROM video_platforms WHERE platform_id=? FOR SHARE", platformId).isEmpty()) {
                throw new AdminService.Rejected(404, "platform_unavailable");
            }
            if (db.queryForList("SELECT 1 FROM cameras WHERE camera_id=? FOR SHARE", req.cameraId()).isEmpty()) {
                throw new AdminService.Rejected(404, "camera_unavailable");
            }

            var existing = db.queryForList("""
                SELECT * FROM platform_camera_channels WHERE platform_id=? AND device_id=? AND channel_id=? FOR UPDATE
                """, platformId, req.deviceId(), req.channelId());
            UUID mappingId;
            try {
                if (existing.isEmpty()) {
                    if (req.expectedRevision() != 0) throw new AdminService.Rejected(409, "platform_revision_conflict");
                    Long count = db.queryForObject("SELECT count(*) FROM platform_camera_channels", Long.class);
                    if (count != null && count >= MAX_CHANNELS) throw new AdminService.Rejected(409, "platform_registry_full");
                    mappingId = UUID.randomUUID();
                    db.update("""
                        INSERT INTO platform_camera_channels(mapping_id,platform_id,device_id,channel_id,camera_id,
                            channel_name,stream_profile,clock_verified,clock_offset_ms,active,revision,updated_by)
                        VALUES(?,?,?,?,?,?,?,?,?,?,1,?)
                        """, mappingId, platformId, req.deviceId(), req.channelId(), req.cameraId(),
                        channelName, profile, clockVerified, req.clockOffsetMs(), req.active(), actor);
                } else {
                    long currentRev = ((Number) existing.getFirst().get("revision")).longValue();
                    if (currentRev != req.expectedRevision()) throw new AdminService.Rejected(409, "platform_revision_conflict");
                    mappingId = (UUID) existing.getFirst().get("mapping_id");
                    db.update("""
                        UPDATE platform_camera_channels SET camera_id=?,channel_name=?,stream_profile=?,clock_verified=?,
                            clock_offset_ms=?,active=?,revision=revision+1,updated_by=?,updated_at=clock_timestamp()
                        WHERE mapping_id=?
                        """, req.cameraId(), channelName, profile, clockVerified, req.clockOffsetMs(), req.active(), actor, mappingId);
                }
            } catch (DuplicateKeyException conflict) {
                throw new AdminService.Rejected(409, "platform_mapping_conflict");
            }

            var saved = db.queryForList("""
                SELECT c.*, cam.display_name AS camera_display_name
                FROM platform_camera_channels c JOIN cameras cam ON cam.camera_id=c.camera_id
                WHERE c.mapping_id=?
                """, mappingId).getFirst();
            var response = Map.<String, Object>of("channel", channelView(saved), "request_id", req.requestId().toString());
            saveCommandReceipt(req.requestId(), "platform.channel.upsert", actor, reqSha, response);
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'platform.channel.upsert',?,?)",
                actor, CanonicalJson.encode(Map.of("platform_id", platformId, "device_id", req.deviceId(), "channel_id", req.channelId(), "camera_id", req.cameraId()), 4096).payload(), req.requestId());
            return response;
        });
    }

    public Map<String, Object> acquireSession(AcquireSessionRequest req) {
        if (req == null || req.requestId() == null || req.platformId() == null || req.deviceId() == null
                || req.channelId() == null || req.workerId() == null || !req.workerId().matches("^[A-Za-z0-9_.-]{1,100}$")
                || !Set.of("live", "playback").contains(req.sessionKind()) || req.leaseSeconds() < 15 || req.leaseSeconds() > 600) {
            throw new IllegalArgumentException("invalid session acquire request");
        }
        String profile = req.streamProfile() == null ? "unknown" : req.streamProfile();
        if (!Set.of("main", "sub", "unknown").contains(profile)) throw new IllegalArgumentException("invalid stream profile");
        if ("playback".equals(req.sessionKind()) && (req.sourceStartTime() == null || req.sourceEndTime() == null || req.sourceEndTime().isBefore(req.sourceStartTime()))) {
            throw new IllegalArgumentException("playback window required");
        }
        return tx.execute(status -> {
            db.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
            db.update("UPDATE platform_media_sessions SET state='expired',updated_at=clock_timestamp() WHERE state='active' AND lease_expires_at<=clock_timestamp()");

            var platRows = db.queryForList("SELECT * FROM video_platforms WHERE platform_id=? FOR SHARE", req.platformId());
            if (platRows.isEmpty()) throw new AdminService.Rejected(404, "platform_unavailable");
            var platform = toPlatformSpec(platRows.getFirst());
            if (!platform.enabled()) throw new AdminService.Rejected(409, "platform_disabled");

            var chanRows = db.queryForList("""
                SELECT c.*, cam.enabled AS camera_enabled FROM platform_camera_channels c
                JOIN cameras cam ON cam.camera_id=c.camera_id
                WHERE c.platform_id=? AND c.device_id=? AND c.channel_id=? FOR SHARE
                """, req.platformId(), req.deviceId(), req.channelId());
            if (chanRows.isEmpty()) throw new AdminService.Rejected(404, "platform_channel_unavailable");
            var chanRow = chanRows.getFirst();
            if (!Boolean.TRUE.equals(chanRow.get("active")) || !Boolean.TRUE.equals(chanRow.get("camera_enabled"))) {
                throw new AdminService.Rejected(409, "platform_channel_disabled");
            }
            var mapping = toChannelMapping(chanRow);
            Long activeCount = db.queryForObject("SELECT count(*) FROM platform_media_sessions WHERE state='active'", Long.class);
            if (activeCount != null && activeCount >= MAX_ACTIVE_SESSIONS) throw new AdminService.Rejected(429, "platform_session_limit");

            Long nextEpoch = db.queryForObject("SELECT COALESCE(MAX(media_epoch)+1,0) FROM platform_media_sessions WHERE camera_id=?", Long.class, mapping.cameraId());
            long epoch = nextEpoch == null ? 0L : nextEpoch;
            UUID sessionId = UUID.randomUUID();
            UUID leaseToken = UUID.randomUUID();
            var impl = adapter(platform.adapterKind());
            var plan = "live".equals(req.sessionKind())
                ? impl.acquireLive(platform, mapping, profile, sessionId, epoch)
                : impl.acquirePlayback(platform, mapping, req.sourceStartTime(), req.sourceEndTime(), profile, sessionId, epoch);

            db.update("""
                INSERT INTO platform_media_sessions(session_id,platform_id,device_id,channel_id,camera_id,adapter_kind,
                    session_kind,stream_profile,media_epoch,state,lease_owner,lease_token,lease_expires_at,
                    media_handle_ref,source_start_time,source_end_time,clock_state)
                VALUES(?,?,?,?,?,?,?,?,?,'active',?,?,clock_timestamp()+make_interval(secs=>?),?,?,?,?)
                """, sessionId, platform.platformId(), mapping.deviceId(), mapping.channelId(), mapping.cameraId(), platform.adapterKind(),
                req.sessionKind(), profile, epoch, req.workerId(), leaseToken, req.leaseSeconds(),
                plan.mediaHandleRef(), ts(req.sourceStartTime()), ts(req.sourceEndTime()), plan.clockState());

            var row = db.queryForList("SELECT * FROM platform_media_sessions WHERE session_id=?", sessionId).getFirst();
            var view = sessionView(row, true);
            view.put("interoperability_verified", plan.interoperabilityVerified());
            view.put("control_reachable", plan.controlReachable());
            return Map.of("session", view);
        });
    }

    public Map<String, Object> renewSession(UUID sessionId, RenewSessionRequest req) {
        if (sessionId == null || req == null || req.workerId() == null || req.leaseToken() == null || req.leaseSeconds() < 15 || req.leaseSeconds() > 600) {
            throw new IllegalArgumentException("invalid renew request");
        }
        return tx.execute(status -> {
            int updated = db.update("""
                UPDATE platform_media_sessions SET lease_expires_at=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp()
                WHERE session_id=? AND state='active' AND lease_owner=? AND lease_token=? AND lease_expires_at>clock_timestamp()
                """, req.leaseSeconds(), sessionId, req.workerId(), req.leaseToken());
            if (updated != 1) throw new AdminService.Rejected(409, "platform_session_conflict");
            var row = db.queryForList("SELECT * FROM platform_media_sessions WHERE session_id=?", sessionId).getFirst();
            return Map.of("session", sessionView(row, true));
        });
    }

    public Map<String, Object> releaseSession(UUID sessionId, ReleaseSessionRequest req) {
        if (sessionId == null || req == null || req.workerId() == null || req.leaseToken() == null) {
            throw new IllegalArgumentException("invalid release request");
        }
        return tx.execute(status -> {
            int updated = db.update("""
                UPDATE platform_media_sessions SET state='released',error_code=?,updated_at=clock_timestamp()
                WHERE session_id=? AND state='active' AND lease_owner=? AND lease_token=?
                """, req.reason() == null ? "released" : req.reason().strip().substring(0, Math.min(80, req.reason().strip().length())),
                sessionId, req.workerId(), req.leaseToken());
            if (updated != 1) throw new AdminService.Rejected(409, "platform_session_conflict");
            var row = db.queryForList("SELECT * FROM platform_media_sessions WHERE session_id=?", sessionId).getFirst();
            return Map.of("session", sessionView(row, false));
        });
    }

    public Map<String, Object> listSessions(String cameraId) {
        var allowed = scope.readableCameras().stream().sorted().toList();
        if (allowed.isEmpty()) return Map.of("items", List.of());
        if (cameraId != null && !scope.mayReadCamera(cameraId)) throw new AdminService.Rejected(404, "camera_unavailable");
        var args = new ArrayList<Object>(allowed);
        String sql = "SELECT * FROM platform_media_sessions WHERE camera_id IN (" + String.join(",", Collections.nCopies(allowed.size(), "?")) + ")";
        if (cameraId != null) {
            sql += " AND camera_id=?";
            args.add(cameraId);
        }
        sql += " ORDER BY created_at DESC LIMIT 50";
        return Map.of("items", db.queryForList(sql, args.toArray()).stream().map(r -> sessionView(r, false)).toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkCommandReceipt(UUID requestId, String actor, String op, String reqSha) {
        var rows = db.queryForList("SELECT actor_id,operation,request_sha256,response::text AS payload FROM application_command_receipts WHERE request_id=?", requestId);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!actor.equals(row.get("actor_id")) || !op.equals(row.get("operation")) || !reqSha.equals(row.get("request_sha256"))) {
            throw new AdminService.Rejected(409, "command_request_conflict");
        }
        return JSON.readValue((String) row.get("payload"), Map.class);
    }

    private void saveCommandReceipt(UUID requestId, String op, String actor, String reqSha, Map<String, Object> response) {
        db.update("""
            INSERT INTO application_command_receipts(request_id,operation,actor_id,request_sha256,response)
            VALUES(?,?,?,?,?::jsonb)
            """, requestId, op, actor, reqSha, CanonicalJson.encode(response, 65536).payload());
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static VideoPlatformAdapter.PlatformSpec toPlatformSpec(Map<String, Object> row) {
        return new VideoPlatformAdapter.PlatformSpec(
            (String) row.get("platform_id"), (String) row.get("adapter_kind"),
            (String) row.get("display_name"), (String) row.get("endpoint_ref"),
            (String) row.get("media_node"), (String) row.get("clock_state"),
            row.get("clock_offset_ms") instanceof Number n ? n.longValue() : null,
            Boolean.TRUE.equals(row.get("interoperability_verified")),
            Boolean.TRUE.equals(row.get("enabled")),
            ((Number) row.get("revision")).longValue(),
            (String) row.get("updated_by"),
            row.get("updated_at") instanceof Timestamp t ? t.toInstant() : null
        );
    }

    private static VideoPlatformAdapter.ChannelMapping toChannelMapping(Map<String, Object> row) {
        return new VideoPlatformAdapter.ChannelMapping(
            (UUID) row.get("mapping_id"), (String) row.get("platform_id"),
            (String) row.get("device_id"), (String) row.get("channel_id"),
            (String) row.get("camera_id"), (String) row.get("channel_name"),
            (String) row.get("stream_profile"), Boolean.TRUE.equals(row.get("clock_verified")),
            row.get("clock_offset_ms") instanceof Number n ? n.longValue() : null,
            Boolean.TRUE.equals(row.get("active")), Map.of(),
            ((Number) row.get("revision")).longValue(),
            (String) row.get("updated_by"),
            row.get("updated_at") instanceof Timestamp t ? t.toInstant() : null
        );
    }

    private static Map<String, Object> platformView(VideoPlatformAdapter.PlatformSpec spec) {
        var view = new LinkedHashMap<String, Object>();
        view.put("platform_id", spec.platformId());
        view.put("adapter_kind", spec.adapterKind());
        view.put("display_name", spec.displayName());
        view.put("endpoint_ref", spec.endpointRef());
        view.put("media_node", spec.mediaNode());
        view.put("clock_state", spec.clockState());
        view.put("clock_offset_ms", spec.clockOffsetMs());
        view.put("interoperability_verified", spec.interoperabilityVerified());
        view.put("enabled", spec.enabled());
        view.put("revision", spec.revision());
        view.put("updated_by", spec.updatedBy());
        view.put("updated_at", spec.updatedAt() == null ? null : spec.updatedAt().toString());
        return view;
    }

    private Map<String, Object> channelView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("mapping_id", row.get("mapping_id").toString());
        view.put("platform_id", row.get("platform_id"));
        view.put("device_id", row.get("device_id"));
        view.put("channel_id", row.get("channel_id"));
        view.put("camera_id", row.get("camera_id"));
        if (row.containsKey("camera_display_name")) view.put("camera_display_name", row.get("camera_display_name"));
        view.put("channel_name", row.get("channel_name"));
        view.put("stream_profile", row.get("stream_profile"));
        view.put("clock_verified", Boolean.TRUE.equals(row.get("clock_verified")));
        view.put("clock_offset_ms", row.get("clock_offset_ms"));
        view.put("active", Boolean.TRUE.equals(row.get("active")));
        view.put("revision", ((Number) row.get("revision")).longValue());
        view.put("updated_by", row.get("updated_by"));
        view.put("updated_at", row.get("updated_at") instanceof Timestamp t ? t.toInstant().toString() : null);
        return view;
    }

    private static Map<String, Object> sessionView(Map<String, Object> row, boolean includeToken) {
        var view = new LinkedHashMap<String, Object>();
        view.put("session_id", row.get("session_id").toString());
        view.put("platform_id", row.get("platform_id"));
        view.put("device_id", row.get("device_id"));
        view.put("channel_id", row.get("channel_id"));
        view.put("camera_id", row.get("camera_id"));
        view.put("adapter_kind", row.get("adapter_kind"));
        view.put("session_kind", row.get("session_kind"));
        view.put("stream_profile", row.get("stream_profile"));
        view.put("media_epoch", ((Number) row.get("media_epoch")).longValue());
        view.put("state", row.get("state"));
        view.put("lease_owner", row.get("lease_owner"));
        if (includeToken) view.put("lease_token", row.get("lease_token").toString());
        view.put("lease_expires_at", row.get("lease_expires_at") instanceof Timestamp t ? t.toInstant().toString() : null);
        view.put("media_handle_ref", row.get("media_handle_ref"));
        view.put("clock_state", row.get("clock_state"));
        view.put("error_code", row.get("error_code"));
        return view;
    }
}
