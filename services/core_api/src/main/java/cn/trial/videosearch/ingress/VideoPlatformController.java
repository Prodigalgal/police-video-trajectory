package cn.trial.videosearch.ingress;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Admin and internal worker HTTP boundaries for ADR023 WVP-PRO / ZLMediaKit platform adapters. */
@RestController
public class VideoPlatformController {
    private final VideoPlatformService service;
    private final UserService users;
    private final byte[] adminExpected;
    private final byte[] workerExpected;

    public VideoPlatformController(
        VideoPlatformService service,
        UserService users,
        @Value("${VIDEO_ADMIN_TOKEN:}") String adminToken,
        @Value("${VIDEO_WORKER_TOKEN:}") String workerToken
    ) {
        this.service = service;
        this.users = users;
        this.adminExpected = adminToken.isBlank() ? null : ("Bearer " + adminToken).getBytes(StandardCharsets.UTF_8);
        this.workerExpected = workerToken.isBlank() ? null : ("Bearer " + workerToken).getBytes(StandardCharsets.UTF_8);
    }

    private Mono<ResponseEntity<Map<String, Object>>> adminCall(String auth, String actor, Long revision, Supplier<Map<String, Object>> action) {
        if (adminExpected == null) return Mono.just(response(503, Map.of("code", "admin_unavailable")));
        if (auth == null || auth.length() > 1024 || !MessageDigest.isEqual(adminExpected, auth.getBytes(StandardCharsets.UTF_8))
                || actor == null || !actor.matches("[A-Za-z0-9_.@-]{1,100}")) {
            return Mono.just(response(401, Map.of("code", "unauthorized")));
        }
        return Mono.fromCallable(() -> {
            if (revision != null && !Boolean.TRUE.equals(users.status(actor, revision).get("valid"))) {
                throw new AdminService.Rejected(401, "unauthorized");
            }
            users.requireAdmin(actor);
            return response(200, action.get());
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(AdminService.Rejected.class, e -> Mono.just(response(e.status, Map.of("code", e.code))))
          .onErrorResume(IllegalArgumentException.class, e -> Mono.just(response(400, Map.of("code", "invalid_admin_request"))))
          .onErrorResume(Exception.class, e -> Mono.just(response(503, Map.of("code", "admin_unavailable"))));
    }

    private Mono<ResponseEntity<Map<String, Object>>> workerCall(String auth, Supplier<Map<String, Object>> action) {
        if (workerExpected == null) return Mono.just(response(503, Map.of("code", "platform_unavailable")));
        if (auth == null || auth.length() > 1024 || !MessageDigest.isEqual(workerExpected, auth.getBytes(StandardCharsets.UTF_8))) {
            return Mono.just(response(401, Map.of("code", "unauthorized")));
        }
        return Mono.fromCallable(() -> response(200, action.get())).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(AdminService.Rejected.class, e -> Mono.just(response(e.status, Map.of("code", e.code))))
          .onErrorResume(IllegalArgumentException.class, e -> Mono.just(response(400, Map.of("code", "invalid_platform_request"))))
          .onErrorResume(Exception.class, e -> Mono.just(response(503, Map.of("code", "platform_unavailable"))));
    }

    private static ResponseEntity<Map<String, Object>> response(int status, Map<String, Object> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/internal/admin/platforms")
    public Mono<ResponseEntity<Map<String, Object>>> platforms(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestHeader(value = "X-Actor-Id", required = false) String actor,
        @RequestHeader(value = "X-Actor-Revision", required = false) Long revision
    ) {
        return adminCall(auth, actor, revision, service::listPlatforms);
    }

    @PutMapping("/internal/admin/platforms/{platformId}")
    public Mono<ResponseEntity<Map<String, Object>>> upsertPlatform(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestHeader(value = "X-Actor-Id", required = false) String actor,
        @RequestHeader(value = "X-Actor-Revision", required = false) Long revision,
        @PathVariable String platformId,
        @RequestBody VideoPlatformService.PlatformUpsert body
    ) {
        return adminCall(auth, actor, revision, () -> service.upsertPlatform(platformId, body, actor));
    }

    @GetMapping("/internal/admin/platforms/{platformId}/channels")
    public Mono<ResponseEntity<Map<String, Object>>> channels(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestHeader(value = "X-Actor-Id", required = false) String actor,
        @RequestHeader(value = "X-Actor-Revision", required = false) Long revision,
        @PathVariable String platformId
    ) {
        return adminCall(auth, actor, revision, () -> service.listChannels(platformId));
    }

    @PutMapping("/internal/admin/platforms/{platformId}/channels")
    public Mono<ResponseEntity<Map<String, Object>>> upsertChannel(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestHeader(value = "X-Actor-Id", required = false) String actor,
        @RequestHeader(value = "X-Actor-Revision", required = false) Long revision,
        @PathVariable String platformId,
        @RequestBody VideoPlatformService.ChannelMappingUpsert body
    ) {
        return adminCall(auth, actor, revision, () -> service.upsertChannelMapping(platformId, body, actor));
    }

    @GetMapping("/internal/admin/platforms/sessions")
    public Mono<ResponseEntity<Map<String, Object>>> sessions(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestHeader(value = "X-Actor-Id", required = false) String actor,
        @RequestHeader(value = "X-Actor-Revision", required = false) Long revision,
        @RequestParam(required = false) String camera
    ) {
        return adminCall(auth, actor, revision, () -> service.listSessions(camera));
    }

    @GetMapping("/internal/v1/platforms/capabilities")
    public Mono<ResponseEntity<Map<String, Object>>> workerCapabilities(@RequestHeader(value = "Authorization", required = false) String auth) {
        return workerCall(auth, service::capabilities);
    }

    @PostMapping("/internal/v1/platforms/sessions/acquire")
    public Mono<ResponseEntity<Map<String, Object>>> acquireSession(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestBody VideoPlatformService.AcquireSessionRequest body
    ) {
        return workerCall(auth, () -> service.acquireSession(body));
    }

    @PostMapping("/internal/v1/platforms/sessions/{sessionId}/renew")
    public Mono<ResponseEntity<Map<String, Object>>> renewSession(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @PathVariable UUID sessionId,
        @RequestBody VideoPlatformService.RenewSessionRequest body
    ) {
        return workerCall(auth, () -> service.renewSession(sessionId, body));
    }

    @PostMapping("/internal/v1/platforms/sessions/{sessionId}/release")
    public Mono<ResponseEntity<Map<String, Object>>> releaseSession(
        @RequestHeader(value = "Authorization", required = false) String auth,
        @PathVariable UUID sessionId,
        @RequestBody VideoPlatformService.ReleaseSessionRequest body
    ) {
        return workerCall(auth, () -> service.releaseSession(sessionId, body));
    }
}
