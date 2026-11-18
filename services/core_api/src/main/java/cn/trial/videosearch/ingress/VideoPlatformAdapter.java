package cn.trial.videosearch.ingress;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** ADR023 video platform ingress port. Unverified clocks or field platforms never forge verified source times. */
public interface VideoPlatformAdapter {
    String adapterKind();
    String protocolLabel();
    Map<String, Object> capabilities();
    Map<String, Object> health(PlatformSpec platform);
    Map<String, Object> discoverChannels(PlatformSpec platform, int offset, int limit);
    SessionLeasePlan acquireLive(PlatformSpec platform, ChannelMapping mapping, String streamProfile, UUID sessionId, long mediaEpoch);
    Map<String, Object> queryRecordings(PlatformSpec platform, ChannelMapping mapping, Instant startTime, Instant endTime);
    SessionLeasePlan acquirePlayback(PlatformSpec platform, ChannelMapping mapping, Instant startTime, Instant endTime, String streamProfile, UUID sessionId, long mediaEpoch);
    Map<String, Object> renewSession(PlatformSpec platform, MediaSession session, int leaseSeconds);
    Map<String, Object> releaseSession(PlatformSpec platform, MediaSession session, String reason);

    record PlatformSpec(
        String platformId,
        String adapterKind,
        String displayName,
        String endpointRef,
        String mediaNode,
        String clockState,
        Long clockOffsetMs,
        boolean interoperabilityVerified,
        boolean enabled,
        long revision,
        String updatedBy,
        Instant updatedAt
    ) {}

    record ChannelMapping(
        UUID mappingId,
        String platformId,
        String deviceId,
        String channelId,
        String cameraId,
        String channelName,
        String streamProfile,
        boolean clockVerified,
        Long clockOffsetMs,
        boolean active,
        Map<String, Object> capabilities,
        long revision,
        String updatedBy,
        Instant updatedAt
    ) {}

    record MediaSession(
        UUID sessionId,
        String platformId,
        String deviceId,
        String channelId,
        String cameraId,
        String adapterKind,
        String sessionKind,
        String streamProfile,
        long mediaEpoch,
        String state,
        String leaseOwner,
        UUID leaseToken,
        Instant leaseExpiresAt,
        String mediaHandleRef,
        Instant sourceStartTime,
        Instant sourceEndTime,
        String clockState,
        String errorCode,
        Instant updatedAt
    ) {}

    record SessionLeasePlan(
        String mediaHandleRef,
        String clockState,
        boolean controlReachable,
        boolean interoperabilityVerified,
        Map<String, Object> metadata
    ) {}

    /** Resolve absolute source time only when platform clock synchronization has been verified. */
    static Optional<Instant> resolveVerifiedSourceTime(boolean platformClockVerified, Long clockOffsetMs, Instant rawPlatformTime) {
        if (!platformClockVerified || rawPlatformTime == null) {
            return Optional.empty();
        }
        long offset = clockOffsetMs == null ? 0L : clockOffsetMs;
        return Optional.of(rawPlatformTime.plusMillis(offset));
    }
}
