package cn.trial.videosearch.search;

import cn.trial.videosearch.http.SecureJsonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validated interpretation and authorized, paged ES retrieval over observation semantics. */
@Service
public final class SearchOrchestrator {
    private static final Set<String> RESULT_FIELDS = Set.of("observation_id", "frame_id", "camera_id", "target_type", "bbox_xyxy", "uncertain_fields", "source_type", "media_offset_ms",
            "source_time_origin", "source_time", "received_at", "upper_color", "lower_color", "vehicle_color", "carrying", "transport_type",
            "visible_action", "description", "model_id", "prompt_version", "record_version", "target_id", "track_id", "detector_class", "detector_confidence", "association");
    private final JdbcTemplate database;
    private final SecureJsonClient gateway;
    private final SecureJsonClient elasticsearch;
    private final String gatewayAuthorization;
    private final String elasticAuthorization;
    private final byte[] searchToken;
    private final Set<String> allowedCameras;
    private final String indexName;
    private final SearchCursorCodec cursorCodec;
    private final SearchPaging paging;
    private final SearchContextCodec contextCodec;
    private final SearchGuidance guidance;

    public SearchOrchestrator(JdbcTemplate database,
            @Value("${VIDEO_GATEWAY_URL:}") String gatewayUrl, @Value("${VIDEO_GATEWAY_CA:}") String gatewayCa,
            @Value("${VIDEO_GATEWAY_TOKEN:}") String gatewayToken, @Value("${VIDEO_ES_URL:}") String esUrl,
            @Value("${VIDEO_ES_CA:}") String esCa, @Value("${VIDEO_ES_USER:}") String esUser,
            @Value("${VIDEO_ES_PASSWORD:}") String esPassword, @Value("${VIDEO_SEARCH_TOKEN:}") String token,
            @Value("${VIDEO_ALLOWED_CAMERAS:}") String cameras,
            @Value("${VIDEO_ES_INDEX:police-video-observations}") String indexName) {
        if (gatewayToken.isBlank() || esUser.isBlank() || esPassword.isBlank() || token.isBlank()) {
            throw new IllegalArgumentException("trial service credentials must be configured");
        }
        this.database = database;
        this.gateway = new SecureJsonClient(gatewayUrl, gatewayCa);
        this.elasticsearch = new SecureJsonClient(esUrl, esCa);
        this.gatewayAuthorization = "Bearer " + gatewayToken;
        this.elasticAuthorization = "Basic " + Base64.getEncoder().encodeToString((esUser + ":" + esPassword).getBytes(StandardCharsets.UTF_8));
        this.searchToken = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        var allowed = new HashSet<String>();
        for (String camera : cameras.split(",")) {
            if (!camera.strip().matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("camera allowlist must be configured");
            allowed.add(camera.strip());
        }
        if (allowed.isEmpty() || allowed.size() > 50) throw new IllegalArgumentException("invalid camera allowlist");
        this.allowedCameras = Set.copyOf(allowed);
        this.indexName = SecureJsonClient.validateIndex(indexName);
        String namespace=indexName+":"+String.join(",",new java.util.TreeSet<>(allowedCameras));
        this.cursorCodec = new SearchCursorCodec(token,namespace);
        this.paging = new SearchPaging(elasticsearch,elasticAuthorization,this.indexName,cursorCodec);
        this.contextCodec=new SearchContextCodec(token,namespace);
        this.guidance=new SearchGuidance(database,allowedCameras,contextCodec);
    }

    public boolean authorized(String provided) {
        return provided != null && provided.length() <= 1024 && MessageDigest.isEqual(searchToken, provided.getBytes(StandardCharsets.UTF_8));
    }

    public boolean mayReadCamera(String cameraId) { return cameraId != null && allowedCameras.contains(cameraId); }
    public Set<String> readableCameras() { return allowedCameras; }

    public Map<String, Object> search(String input) {
        return search(input,20,null);
    }

    public Map<String,Object> search(String input,int pageSize,String cursor) {
        return search(input,pageSize,cursor,null);
    }

    public Map<String,Object> search(String input,int pageSize,String cursor,SearchRefinement.Command adjustment) {
        return search(input,pageSize,cursor,adjustment,"natural");
    }

    public Map<String,Object> search(String input,int pageSize,String cursor,SearchRefinement.Command adjustment,String mode) {
        if (input == null || input.isBlank() || input.length() > 1000) throw new IllegalArgumentException("invalid query");
        if(pageSize<1 || pageSize>50) throw new IllegalArgumentException("invalid page size");
        KeywordSearchPlanner.validate(mode,input,cursor,adjustment);
        if(cursor!=null&&adjustment!=null)throw new SearchContextCodec.Invalid("invalid_search_adjustment");
        UUID requestId = UUID.randomUUID();
        String outcome = "failed";
        try {
            var continuation = cursor==null ? null : cursorCodec.decode(cursor,input);
            var previous=adjustment==null?null:contextCodec.decode(adjustment.context(),input);
            Set<String> priorLocation=previous!=null&&previous.plan().locationText()!=null&&!SearchRefinement.replacesLocation(adjustment)?resolveLocation(previous.plan().locationText()):null;
            if(previous!=null&&previous.plan().locationText()!=null&&!SearchRefinement.replacesLocation(adjustment)
                    &&!priorLocation.equals(Set.copyOf(previous.locationCameras())))throw new SearchContextCodec.Invalid("search_scope_changed");
            SearchPlan plan=previous!=null?SearchRefinement.apply(previous.plan(),adjustment,allowedCameras):continuation==null
                    ?initialPlan(input,mode):continuation.plan();
            long expires=previous!=null?previous.expires():continuation!=null?continuation.expires():contextCodec.now()+SearchContextCodec.TTL_SECONDS;
            Set<String> location=plan.locationText()==null?null:priorLocation!=null?priorLocation:resolveLocation(plan.locationText());
            List<String> questions=plan.clarifications();String reason="plan_incomplete";
            if(questions.isEmpty()&&location!=null&&location.isEmpty()){
                questions=List.of("请明确要查询的地点，或选择下方监控点位");reason="location_unresolved";
            }
            if(questions.isEmpty()&&!SearchQueryCompiler.hasConditions(plan)){
                questions=List.of("请补充要查找的人车特征、时间或监控点位");reason="conditions_required";
            }
            if(!questions.isEmpty()){
                outcome="clarification";
                return Map.of("request_id",requestId.toString(),"status","clarification_required","query",input.strip(),"plan",plan,"questions",questions,
                        "guidance",guidance.describe(plan,location,input,expires,reason));
            }
            Map<String, Object> query;
            try { query = SearchQueryCompiler.compile(plan, allowedCameras, location, pageSize); }
            catch (IllegalArgumentException e) { throw new SearchFailure("invalid_search_scope"); }
            var searchGuidance=new LinkedHashMap<>(guidance.describe(plan,location,input,expires,"ready"));
            List<Map<String, Object>> hits;
            long total;
            Map<String,Object> pagination;
            try {
                var page = paging.fetch(input,plan,query,pageSize,continuation,previous==null?null:previous.expires());
                hits = SearchMatchEvidence.annotate(safeHits(page.result()), plan);
                total = totalHits(page.result());pagination=page.pagination();
            } catch (SecureJsonClient.UpstreamFailure e) { throw new SearchFailure("search_backend_unavailable"); }
            if(total==0)searchGuidance.put("reason","no_matches");
            outcome = "ok";
            return Map.of("request_id", requestId.toString(), "status", "ok", "plan", plan, "results", hits, "total", total, "query", input.strip(),
                    "pagination", pagination,
                    "retrieval", SearchMatchEvidence.summary(hits),
                    "coverage", Map.of("status", "unknown", "message", "尚未确认全部视频的处理和索引覆盖范围"),
                    "guidance",searchGuidance);
        } finally {
            database.update("INSERT INTO audit_events(actor_id, action, result, request_id) VALUES (?, ?, ?, ?)",
                    "trial-service-account", "search", outcome, requestId);
        }
    }

    public void closeSearch(String input,String cursor) {
        if(input==null || input.isBlank() || input.length()>1000 || cursor==null) throw new IllegalArgumentException("invalid close request");
        paging.close(input,cursor);
    }

    private SearchPlan initialPlan(String input,String mode) {
        if("keyword".equals(mode))return KeywordSearchPlanner.plan(input);
        return TemporalSearchPlanner.parse(input,Instant.now(),java.time.ZoneId.of("Asia/Shanghai")).orElseGet(()->interpret(input));
    }

    private SearchPlan interpret(String input) {
        Map<String, Object> model;
        try {
            model = gateway.post("/internal/v1/interpret-query", Map.of("query", input.strip(),
                    "reference_time", Instant.now().toString(), "timezone", "Asia/Shanghai"), gatewayAuthorization, Duration.ofSeconds(370));
        } catch (SecureJsonClient.UpstreamFailure e) { throw new SearchFailure("model_unavailable"); }
        try { return SearchPlanParser.parse(model.get("result")); }
        catch (IllegalArgumentException e) { throw new SearchFailure("invalid_model_plan"); }
    }

    public static final class SearchFailure extends RuntimeException {
        private final String code;
        public SearchFailure(String code) { super("search dependency unavailable"); this.code = code; }
        public String code() { return code; }
    }

    private Set<String> resolveLocation(String alias) {
        var rows = database.queryForList("SELECT c.camera_id, c.location_key FROM cameras c JOIN camera_aliases a ON a.camera_id = c.camera_id WHERE c.enabled AND a.alias = ?", alias);
        return resolveLocationRows(rows,allowedCameras);
    }

    static Set<String> resolveLocationRows(List<Map<String,Object>> rows,Set<String> allowedCameras) {
        var cameras = new HashSet<String>();
        var locations = new HashSet<String>();
        boolean missingRegion=false;
        for (var row : rows) {
            if (!(row.get("camera_id") instanceof String id) || !allowedCameras.contains(id)) continue;
            cameras.add(id);
            if (row.get("location_key") instanceof String location && !location.isBlank()) locations.add(location);
            else missingRegion=true;
        }
        if(cameras.size()==1) return Set.copyOf(cameras);
        return !missingRegion && locations.size() == 1 ? Set.copyOf(cameras) : Set.of();
    }

    private List<Map<String, Object>> safeHits(Map<String, Object> result) {
        if (!(result.get("hits") instanceof Map<?, ?> hits) || !(hits.get("hits") instanceof List<?> list) || list.size() > 50) {
            throw new SecureJsonClient.UpstreamFailure();
        }
        var sanitized = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> hit) || !(hit.get("_source") instanceof Map<?, ?> source) ||
                    !allowedCameras.contains(source.get("camera_id")) || !Boolean.FALSE.equals(source.get("deleted"))) {
                throw new SecureJsonClient.UpstreamFailure();
            }
            var safe = new LinkedHashMap<String, Object>();
            for (String field : RESULT_FIELDS) if (source.containsKey(field)) safe.put(field, source.get(field));
            if (hit.get("_score") instanceof Number score && Double.isFinite(score.doubleValue()) && score.doubleValue() >= 0)
                safe.put("relevance_score", score.doubleValue());
            if (hit.get("matched_queries") instanceof List<?> matched && matched.size() <= 40 &&
                    matched.stream().allMatch(v -> v instanceof String s && s.matches("semantic_[0-9]{1,2}")))
                safe.put("matched_queries", matched);
            sanitized.add(safe);
        }
        return List.copyOf(sanitized);
    }

    private long totalHits(Map<String,Object> result) {
        if (result.get("hits") instanceof Map<?,?> hits && hits.get("total") instanceof Map<?,?> total &&
                "eq".equals(total.get("relation")) && total.get("value") instanceof Number value && value.longValue() >= 0)
            return value.longValue();
        throw new SecureJsonClient.UpstreamFailure();
    }
}
