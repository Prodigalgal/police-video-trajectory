package cn.trial.videosearch.search;

import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Signed, bounded continuation state. Tokens cannot introduce an ES query. */
public final class SearchCursorCodec {
    public record State(SearchPlan plan, String pit, List<Object> after, int size, long page,
                        boolean backwards, long expires, String fingerprint) {}
    public static final class Invalid extends RuntimeException {
        public final String code;
        public Invalid(String code) { super(code); this.code=code; }
    }
    private final byte[] key;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    public SearchCursorCodec(String token, String namespace) { this(token,namespace,Clock.systemUTC()); }
    SearchCursorCodec(String token,String namespace,Clock clock) {
        this.key=digest(("video-search-cursor-v1\0"+namespace+"\0"+token).getBytes(StandardCharsets.UTF_8)); this.clock=clock;
    }
    public long now() { return clock.instant().getEpochSecond(); }
    public String encode(State s,String input) {
        var body=new LinkedHashMap<String,Object>();
        body.put("v",1);body.put("q",hash(input.strip()));body.put("plan",SearchPlanParser.document(s.plan()));body.put("pit",s.pit());
        body.put("after",s.after());body.put("size",s.size());body.put("page",s.page());body.put("back",s.backwards());
        body.put("expires",s.expires());body.put("fingerprint",s.fingerprint());
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(body));
        String token=payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
        if(token.length()>32768) throw new IllegalStateException("search cursor exceeds bound");
        return token;
    }
    @SuppressWarnings("unchecked")
    public State decode(String cursor,String input) {
        try {
            if(cursor==null || cursor.length()>32768) throw new IllegalArgumentException();
            String[] parts=cursor.split("\\.",-1);
            if(parts.length!=2 || !MessageDigest.isEqual(mac(parts[0]),Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            var body=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Map.class);
            if(!Objects.equals(body.get("v"),1) || !hash(input.strip()).equals(body.get("q"))) throw new IllegalArgumentException();
            long expires=((Number)body.get("expires")).longValue();
            if(expires<=now()) throw new Invalid("search_cursor_expired");
            if(expires>now()+901) throw new IllegalArgumentException();
            int size=((Number)body.get("size")).intValue();long page=((Number)body.get("page")).longValue();
            String pit=(String)body.get("pit");String fingerprint=(String)body.get("fingerprint");
            var after=(List<Object>)body.get("after");boolean back=(Boolean)body.get("back");
            if(size<1 || size>50 || page<1 || page>1_000_000_000L || pit==null || pit.isBlank() || pit.length()>16000
                    || fingerprint==null || !fingerprint.matches("[a-f0-9]{64}") || after==null) throw new IllegalArgumentException();
            if(!after.isEmpty()) validateSort(after);
            if(back && (after.isEmpty() || page<1)) throw new IllegalArgumentException();
            return new State(SearchPlanParser.parse(body.get("plan")),pit,List.copyOf(after),size,page,back,expires,fingerprint);
        } catch(Invalid e) { throw e; }
        catch(Exception e) { throw new Invalid("invalid_search_cursor"); }
    }
    public static void validateSort(List<?> sort) {
        if(sort.size()!=4 || !(sort.get(0) instanceof Number score) || !Double.isFinite(score.doubleValue()) || score.doubleValue()<0
                || !(sort.get(1) instanceof Number) || !(sort.get(2) instanceof String id)
                || !UUID.fromString(id).toString().equals(id) || !(sort.get(3) instanceof Number)) throw new IllegalArgumentException("invalid sort");
    }
    public static String fingerprint(Object value) { return hash(new ObjectMapper().writeValueAsString(canonical(value))); }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put((String)k,canonical(v)));return result; }
        if(value instanceof List<?> list) return list.stream().map(SearchCursorCodec::canonical).toList();
        return value;
    }
    private byte[] mac(String payload) {
        try { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)); }
        catch(Exception e) { throw new IllegalStateException("cursor signing unavailable"); }
    }
    private static String hash(String value) { return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static byte[] digest(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch(Exception e) { throw new IllegalStateException(e); } }
}
