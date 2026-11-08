package cn.trial.videosearch.search;

import cn.trial.videosearch.http.SecureJsonClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** PIT snapshot and bidirectional search_after, without deep offset queries. */
public final class SearchPaging {
    public record Page(Map<String,Object> result,Map<String,Object> pagination) {}
    private final SecureJsonClient es;
    private final String authorization,index;
    private final SearchCursorCodec codec;
    public SearchPaging(SecureJsonClient es,String authorization,String index,SearchCursorCodec codec) {
        this.es=es;this.authorization=authorization;this.index=index;this.codec=codec;
    }
    public Page fetch(String input,SearchPlan plan,Map<String,Object> compiled,int size,SearchCursorCodec.State state) {
        return fetch(input,plan,compiled,size,state,null);
    }
    @SuppressWarnings("unchecked")
    public Page fetch(String input,SearchPlan plan,Map<String,Object> compiled,int size,SearchCursorCodec.State state,Long expiresLimit) {
        String fingerprint=SearchCursorCodec.fingerprint(compiled.get("query"));
        if(state!=null && (state.size()!=size || !fingerprint.equals(state.fingerprint()))) throw new SearchCursorCodec.Invalid("invalid_search_cursor");
        String pit=state==null?null:state.pit();boolean created=false;
        try {
            if(pit==null) {
                var opened=es.post("/"+index+"/_pit?keep_alive=5m",null,authorization,Duration.ofSeconds(10));
                if(!(opened.get("id") instanceof String id) || id.isBlank() || id.length()>16000) throw new SecureJsonClient.UpstreamFailure();
                pit=id;created=true;
            }
            boolean back=state!=null && state.backwards();
            var body=new LinkedHashMap<String,Object>(compiled);body.put("pit",Map.of("id",pit,"keep_alive","5m"));
            body.put("sort",sort(back));body.put("track_scores",true);body.put("timeout","25s");
            if(state!=null && !state.after().isEmpty()) body.put("search_after",state.after());
            var result=es.post("/_search?allow_partial_search_results=false",body,authorization,Duration.ofSeconds(30));
            if(Boolean.TRUE.equals(result.get("timed_out")) || !(result.get("_shards") instanceof Map<?,?> shards)
                    || !(shards.get("failed") instanceof Number failures) || failures.intValue()!=0) throw new SecureJsonClient.UpstreamFailure();
            if(result.get("pit_id") instanceof String current && !current.isBlank() && current.length()<=16000) pit=current;
            if(!(result.get("hits") instanceof Map<?,?> info) || !(info.get("hits") instanceof List<?> rows) || rows.size()>size
                    || !(info.get("total") instanceof Map<?,?> totalInfo) || !"eq".equals(totalInfo.get("relation"))
                    || !(totalInfo.get("value") instanceof Number totalNumber)) throw new SecureJsonClient.UpstreamFailure();
            var hits=new ArrayList<Map<String,Object>>();for(Object row:rows) hits.add((Map<String,Object>)row);
            if(back) Collections.reverse(hits);
            long total=totalNumber.longValue(),page=state==null?1:state.page(),expires=state==null?Math.min(codec.now()+900,expiresLimit==null?Long.MAX_VALUE:expiresLimit):state.expires();
            if(total<0 || (hits.isEmpty() && page!=1)) throw new SearchCursorCodec.Invalid("search_cursor_expired");
            var pagination=new LinkedHashMap<String,Object>();pagination.put("page",page);pagination.put("page_size",size);
            pagination.put("total_pages",total/size+(total%size==0?0:1));pagination.put("next_cursor",null);pagination.put("previous_cursor",null);
            String first=codec.encode(new SearchCursorCodec.State(plan,pit,List.of(),size,1,false,expires,fingerprint),input);
            pagination.put("first_cursor",first);pagination.put("close_cursor",first);pagination.put("expires_at",Instant.ofEpochSecond(expires).toString());
            pagination.put("snapshot",true);pagination.put("idle_timeout_seconds",300);
            if(!hits.isEmpty()) {
                if(page*size<total) pagination.put("next_cursor",codec.encode(new SearchCursorCodec.State(plan,pit,sortValues(hits.getLast()),size,page+1,false,expires,fingerprint),input));
                if(page>1) pagination.put("previous_cursor",codec.encode(new SearchCursorCodec.State(plan,pit,sortValues(hits.getFirst()),size,page-1,true,expires,fingerprint),input));
            } else closePit(pit);
            var reordered=new LinkedHashMap<String,Object>(result);var hitInfo=new LinkedHashMap<String,Object>((Map<String,Object>)info);hitInfo.put("hits",hits);reordered.put("hits",hitInfo);
            return new Page(reordered,pagination);
        } catch(SecureJsonClient.UpstreamFailure e) {
            if(created && pit!=null) closePit(pit);
            if(state!=null && e.statusCode()==404) throw new SearchCursorCodec.Invalid("search_cursor_expired");
            throw e;
        } catch(RuntimeException e) { if(created && pit!=null) closePit(pit);throw e; }
    }
    public void close(String input,String cursor) { es.delete("/_pit",Map.of("id",codec.decode(cursor,input).pit()),authorization,Duration.ofSeconds(10)); }
    private void closePit(String pit) { try { es.delete("/_pit",Map.of("id",pit),authorization,Duration.ofSeconds(10)); } catch(SecureJsonClient.UpstreamFailure ignored) { /* TTL remains bounded. */ } }
    private static List<Object> sortValues(Map<String,Object> hit) {
        if(!(hit.get("sort") instanceof List<?> values)) throw new SecureJsonClient.UpstreamFailure();
        try { SearchCursorCodec.validateSort(values); } catch(IllegalArgumentException e) { throw new SecureJsonClient.UpstreamFailure(); }
        return new ArrayList<Object>(values);
    }
    static List<Map<String,Object>> sort(boolean backwards) {
        return List.of(Map.of("_score",backwards?"asc":"desc"),Map.of("received_at",backwards?"asc":"desc"),
                Map.of("observation_id",backwards?"desc":"asc"),Map.of("_shard_doc",backwards?"desc":"asc"));
    }
}
