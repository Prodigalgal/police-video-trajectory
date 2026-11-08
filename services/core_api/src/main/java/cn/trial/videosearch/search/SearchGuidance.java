package cn.trial.videosearch.search;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;

/** Bounded projection of the exact interpretation; never probes additional ES queries. */
public final class SearchGuidance {
    private final JdbcTemplate database;private final Set<String> allowed;private final SearchContextCodec codec;
    public SearchGuidance(JdbcTemplate database,Set<String> allowed,SearchContextCodec codec){this.database=database;this.allowed=allowed;this.codec=codec;}
    public Map<String,Object> describe(SearchPlan plan,Set<String> locations,String query,long expires,String reason){
        String slots=String.join(",",Collections.nCopies(allowed.size(),"?"));var names=new TreeMap<String,String>();
        for(var row:database.queryForList("SELECT camera_id,display_name FROM cameras WHERE camera_id IN ("+slots+") ORDER BY camera_id",allowed.toArray()))names.put((String)row.get("camera_id"),(String)row.get("display_name"));
        var state=new SearchContextCodec.State(plan,locations==null?List.of():List.copyOf(new TreeSet<>(locations)),expires);
        String context;try{context=codec.encode(state,query);}catch(SearchContextCodec.TooLarge oversized){context=null;}
        boolean editable=context!=null;var result=new LinkedHashMap<String,Object>();
        result.put("schema_version","search-guidance-v1");result.put("context",context);result.put("expires_at",Instant.ofEpochSecond(expires).toString());
        result.put("conditions",SearchRefinement.conditions(plan,names).stream().map(value->new SearchRefinement.Condition(value.id(),value.label(),editable&&value.removable())).toList());
        result.put("camera_choices",names.entrySet().stream().map(entry->Map.of("camera_id",entry.getKey(),"label",entry.getValue())).toList());result.put("reason",reason);return result;
    }
}
