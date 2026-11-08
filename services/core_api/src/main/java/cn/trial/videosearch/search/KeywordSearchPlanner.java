package cn.trial.videosearch.search;

import java.util.List;
import java.util.Set;

/** Explicit literal retrieval, never an automatic replacement for a failed interpretation. */
public final class KeywordSearchPlanner {
    private KeywordSearchPlanner() {}

    public static void validate(String mode,String query,String cursor,SearchRefinement.Command adjustment) {
        if(mode==null||!Set.of("natural","keyword").contains(mode))throw new IllegalArgumentException("invalid search mode");
        if("keyword".equals(mode)&&(cursor!=null||adjustment!=null||query==null||query.isBlank()||query.strip().length()>200))
            throw new IllegalArgumentException("keyword mode requires a new bounded query");
    }

    public static SearchPlan plan(String query) {
        validate("keyword",query,null,null);
        return new SearchPlan("unknown",null,null,null,List.of(),null,List.of(query.strip()),List.of());
    }
}
