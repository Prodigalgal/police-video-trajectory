package cn.trial.videosearch.search;

import cn.trial.videosearch.support.CanonicalJson;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Domain-separated signatures: a refinement context cannot be used as a paging cursor. */
public final class SearchContextCodec {
    public static final int TTL_SECONDS=900,MAX_TOKEN_LENGTH=32768;
    public record State(SearchPlan plan,List<String> locationCameras,long expires){}
    public static final class TooLarge extends RuntimeException {}
    public static final class Invalid extends RuntimeException {
        public final String code;
        public Invalid(String code){super(code);this.code=code;}
    }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final byte[] key;private final Clock clock;
    public SearchContextCodec(String token,String namespace){this(token,namespace,Clock.systemUTC());}
    SearchContextCodec(String token,String namespace,Clock clock){
        this.key=HexFormat.of().parseHex(CanonicalJson.sha256("video-search-refinement-v1\0"+namespace+"\0"+token));this.clock=clock;
    }
    public long now(){return clock.instant().getEpochSecond();}
    public String encode(State state,String query){
        var body=Map.of("v",1,"kind","search-refinement-v1","q",CanonicalJson.sha256(query.strip()),"plan",SearchPlanParser.document(state.plan()),
                "location_cameras",state.locationCameras(),"expires",state.expires());
        byte[] encoded=CanonicalJson.encode(body,Integer.MAX_VALUE).payload().getBytes(StandardCharsets.UTF_8);
        if(encoded.length>24000)throw new TooLarge();
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
        String token=payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
        if(token.length()>MAX_TOKEN_LENGTH)throw new TooLarge();return token;
    }
    public State decode(String token,String query){
        try{
            if(token==null||token.length()>MAX_TOKEN_LENGTH)throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if(parts.length!=2||!MessageDigest.isEqual(mac(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            var body=JSON.readValue(Base64.getUrlDecoder().decode(parts[0]),Map.class);
            if(!Objects.equals(body.get("v"),1)||!"search-refinement-v1".equals(body.get("kind"))||!CanonicalJson.sha256(query.strip()).equals(body.get("q")))throw new IllegalArgumentException();
            long expires=((Number)body.get("expires")).longValue();
            if(expires<=now())throw new Invalid("search_context_expired");if(expires>now()+TTL_SECONDS+1)throw new IllegalArgumentException();
            if(!(body.get("location_cameras") instanceof List<?> cameras)||cameras.size()>50)throw new IllegalArgumentException();
            var locations=new ArrayList<String>();for(Object raw:cameras){if(!(raw instanceof String id)||!id.matches("[A-Za-z0-9_-]{1,100}")||locations.contains(id))throw new IllegalArgumentException();locations.add(id);}
            return new State(SearchPlanParser.parse(body.get("plan")),List.copyOf(locations),expires);
        }catch(Invalid error){throw error;}catch(Exception error){throw new Invalid("invalid_search_context");}
    }
    private byte[] mac(String payload){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));}
        catch(java.security.GeneralSecurityException error){throw new IllegalStateException("Search signing unavailable",error);}
    }
}
