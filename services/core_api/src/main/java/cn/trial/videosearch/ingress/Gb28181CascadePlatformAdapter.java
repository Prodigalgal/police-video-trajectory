package cn.trial.videosearch.ingress;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** WVP-PRO SIP cascade signaling + ZLMediaKit media transport adapter contract (ADR023). */
@Component
public class Gb28181CascadePlatformAdapter implements VideoPlatformAdapter {
    public String adapterKind() { return "gb28181_cascade"; }
    public String protocolLabel() { return "GB/T 28181-2022 / WVP-PRO + ZLMediaKit 级联"; }

    public Map<String, Object> capabilities() {
        return Map.of(
            "adapter_kind", adapterKind(),
            "label", protocolLabel(),
            "signaling", "WVP-PRO SIP/REST",
            "media_server", "ZLMediaKit",
            "supports_live", true,
            "supports_playback", true,
            "supports_directory_sync", true,
            "supports_ptz", false
        );
    }

    public Map<String, Object> health(PlatformSpec platform) {
        var result = new LinkedHashMap<String, Object>();
        result.put("platform_id", platform.platformId());
        result.put("adapter_kind", adapterKind());
        result.put("configured", platform.enabled());
        result.put("control_reachable", false);
        result.put("media_available", false);
        result.put("interoperability_verified", platform.interoperabilityVerified());
        result.put("clock_state", platform.clockState());
        result.put("status_reason", platform.enabled()
            ? (platform.interoperabilityVerified() ? "configured_awaiting_runtime_probe" : "awaiting_live_wvp_zlm_interop")
            : "platform_disabled");
        return result;
    }

    public Map<String, Object> discoverChannels(PlatformSpec platform, int offset, int limit) {
        return Map.of(
            "platform_id", platform.platformId(),
            "adapter_kind", adapterKind(),
            "interoperability_verified", platform.interoperabilityVerified(),
            "items", List.of(),
            "offset", offset,
            "limit", limit,
            "has_more", false,
            "status_reason", "live_wvp_directory_sync_not_attached"
        );
    }

    public SessionLeasePlan acquireLive(PlatformSpec platform, ChannelMapping mapping, String streamProfile, UUID sessionId, long mediaEpoch) {
        String handle = "wvp-zlm://" + platform.platformId() + "/" + mapping.deviceId() + "/" + mapping.channelId() + "/e" + mediaEpoch + "/" + sessionId;
        String clock = (mapping.clockVerified() && "verified".equals(platform.clockState())) ? "verified" : "unverified";
        return new SessionLeasePlan(handle, clock, false, platform.interoperabilityVerified(),
            Map.of("session_mode", "cascade_live", "stream_profile", streamProfile, "media_node", platform.mediaNode()));
    }

    public Map<String, Object> queryRecordings(PlatformSpec platform, ChannelMapping mapping, Instant startTime, Instant endTime) {
        return Map.of(
            "platform_id", platform.platformId(),
            "device_id", mapping.deviceId(),
            "channel_id", mapping.channelId(),
            "clock_state", (mapping.clockVerified() && "verified".equals(platform.clockState())) ? "verified" : "unverified",
            "segments", List.of(),
            "interoperability_verified", platform.interoperabilityVerified()
        );
    }

    public SessionLeasePlan acquirePlayback(PlatformSpec platform, ChannelMapping mapping, Instant startTime, Instant endTime, String streamProfile, UUID sessionId, long mediaEpoch) {
        String handle = "wvp-zlm-playback://" + platform.platformId() + "/" + mapping.deviceId() + "/" + mapping.channelId() + "/e" + mediaEpoch + "/" + sessionId;
        String clock = (mapping.clockVerified() && "verified".equals(platform.clockState())) ? "verified" : "unverified";
        return new SessionLeasePlan(handle, clock, false, platform.interoperabilityVerified(),
            Map.of("session_mode", "cascade_playback", "stream_profile", streamProfile, "media_node", platform.mediaNode()));
    }

    public Map<String, Object> renewSession(PlatformSpec platform, MediaSession session, int leaseSeconds) {
        return Map.of("session_id", session.sessionId().toString(), "adapter_kind", adapterKind(), "renewed", true, "lease_seconds", leaseSeconds);
    }

    public Map<String, Object> releaseSession(PlatformSpec platform, MediaSession session, String reason) {
        return Map.of("session_id", session.sessionId().toString(), "adapter_kind", adapterKind(), "released", true, "reason", reason == null ? "released" : reason);
    }
}
