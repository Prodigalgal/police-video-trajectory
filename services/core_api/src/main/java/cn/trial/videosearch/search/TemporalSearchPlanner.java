package cn.trial.videosearch.search;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Closed grammar for common time prefixes; complex sentences continue to the existing NLP adapter. */
public final class TemporalSearchPlanner {
    private static final Pattern RELATIVE=Pattern.compile("^(今天|昨天|前天)(?:的)?(.+)$");
    private static final Pattern RECENT=Pattern.compile("^(?:最近|近)([1-9][0-9]{0,2})(小时|分钟|天)(?:内|的)?(.+)$");
    private static final Pattern DATE=Pattern.compile("^([0-9]{4}-[0-9]{2}-[0-9]{2})(?:的)?(.+)$");
    private TemporalSearchPlanner(){}
    public static Optional<SearchPlan> parse(String input,Instant now,ZoneId zone){
        var direct=SimpleSearchPlanner.parse(input);if(direct.isPresent()||input==null||input.length()>1000)return direct;
        String text=input.strip().replaceAll("[\\p{Z}\\s]+","").replaceFirst("^(?:请)?(?:查找|搜索|查询|找|查一下)","");
        Instant start,end;String remaining;
        var relative=RELATIVE.matcher(text);var recent=RECENT.matcher(text);var date=DATE.matcher(text);
        if(relative.matches()){
            int days=switch(relative.group(1)){case "昨天"->1;case "前天"->2;default->0;};
            var day=now.atZone(zone).toLocalDate().minusDays(days);start=day.atStartOfDay(zone).toInstant();end=day.plusDays(1).atStartOfDay(zone).toInstant();remaining=relative.group(2);
        }else if(recent.matches()){
            int amount=Integer.parseInt(recent.group(1));long seconds=amount*switch(recent.group(2)){case "小时"->3600L;case "分钟"->60L;default->86400L;};
            if(seconds>31L*86400)return Optional.empty();end=now;start=now.minusSeconds(seconds);remaining=recent.group(3);
        }else if(date.matches()){
            try{var day=LocalDate.parse(date.group(1));start=day.atStartOfDay(zone).toInstant();end=day.plusDays(1).atStartOfDay(zone).toInstant();remaining=date.group(2);}catch(DateTimeParseException invalid){return Optional.empty();}
        }else return Optional.empty();
        final Instant from=start,to=end;
        return SimpleSearchPlanner.parse(remaining).map(plan->new SearchPlan(plan.targetType(),from,to,plan.locationText(),plan.cameraIds(),plan.attributes(),plan.textTerms(),plan.clarifications()));
    }
}
