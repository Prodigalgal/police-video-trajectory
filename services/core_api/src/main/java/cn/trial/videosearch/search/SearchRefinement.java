package cn.trial.videosearch.search;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicit edits to one signed interpretation. Unresolved model questions remain unresolved. */
public final class SearchRefinement {
    public record Command(String context,String action,String condition,List<String> cameraIds){}
    public record Condition(String id,String label,boolean removable){}
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private SearchRefinement(){}
    public static List<Condition> conditions(SearchPlan plan,Map<String,String> names){
        var labels=new LinkedHashMap<String,String>();
        if(!"unknown".equals(plan.targetType()))labels.put("target","目标 · "+("person".equals(plan.targetType())?"人":"车辆"));
        if(plan.start()!=null||plan.end()!=null)labels.put("time","拍摄时间 · "+(plan.start()==null?"不限开始":TIME.format(plan.start()))+" 至 "+(plan.end()==null?"不限结束":TIME.format(plan.end()))+"（北京时间，结束不含）");
        add(labels,"location","地点",plan.locationText());
        add(labels,"plate","已复核车牌",plan.plateNumber());
        if(!plan.cameraIds().isEmpty())labels.put("cameras","点位 · "+String.join("、",plan.cameraIds().stream().map(id->names.getOrDefault(id,id)).toList()));
        var attributes=plan.attributes();
        if(attributes!=null){
            add(labels,"upper_color","上衣颜色",attributes.upperColor());add(labels,"lower_color","下装颜色",attributes.lowerColor());
            add(labels,"clothing_color","衣着颜色",attributes.clothingColor());add(labels,"vehicle_color","车身颜色",attributes.vehicleColor());
            add(labels,"transport_type","交通工具",attributes.transportType());add(labels,"visible_action","可见动作",attributes.visibleAction());
            for(int index=0;index<attributes.carrying().size();index++)add(labels,"carrying:"+index,"携带物",attributes.carrying().get(index));
        }
        for(int index=0;index<plan.textTerms().size();index++)add(labels,"text:"+index,"语义描述",plan.textTerms().get(index));
        return labels.entrySet().stream().map(entry->new Condition(entry.getKey(),entry.getValue(),
                !(entry.getKey().equals("target")&&(plan.plateNumber()!=null||attributes!=null&&attributes.vehicleColor()!=null))&&SearchQueryCompiler.hasConditions(without(plan,entry.getKey())))).toList();
    }
    private static void add(Map<String,String> labels,String id,String label,String value){if(value!=null&&!value.isBlank())labels.put(id,label+" · "+value);}
    public static boolean replacesLocation(Command command){return command!=null&&("choose_cameras".equals(command.action())||"remove".equals(command.action())&&"location".equals(command.condition()));}
    public static SearchPlan apply(SearchPlan plan,Command command,Set<String> allowed){
        if(command==null||command.context()==null||command.context().length()>SearchContextCodec.MAX_TOKEN_LENGTH)throw new SearchContextCodec.Invalid("invalid_search_adjustment");
        if("remove".equals(command.action())){
            if(command.cameraIds()!=null||command.condition()==null||conditions(plan,Map.of()).stream().noneMatch(value->value.id().equals(command.condition())&&value.removable()))throw new SearchContextCodec.Invalid("invalid_search_adjustment");
            return without(plan,command.condition());
        }
        if("choose_cameras".equals(command.action())){
            var cameras=command.cameraIds();
            if(command.condition()!=null||cameras==null||cameras.isEmpty()||cameras.size()>50||new HashSet<>(cameras).size()!=cameras.size()||!allowed.containsAll(cameras))throw new SearchContextCodec.Invalid("invalid_search_adjustment");
            return new SearchPlan(plan.targetType(),plan.start(),plan.end(),null,List.copyOf(new TreeSet<>(cameras)),plan.attributes(),plan.textTerms(),plan.clarifications(),plan.plateNumber());
        }
        throw new SearchContextCodec.Invalid("invalid_search_adjustment");
    }
    private static List<String> removeTerm(List<String> values,String condition,String prefix){
        if(!condition.startsWith(prefix))return values;
        int index=Integer.parseInt(condition.substring(prefix.length()));var updated=new ArrayList<>(values);updated.remove(index);return List.copyOf(updated);
    }
    private static SearchPlan without(SearchPlan plan,String condition){
        var attributes=plan.attributes();
        if(attributes!=null)attributes=new SearchPlan.Attributes(
                condition.equals("upper_color")?null:attributes.upperColor(),condition.equals("lower_color")?null:attributes.lowerColor(),
                condition.equals("clothing_color")?null:attributes.clothingColor(),removeTerm(attributes.carrying(),condition,"carrying:"),
                condition.equals("transport_type")?null:attributes.transportType(),condition.equals("visible_action")?null:attributes.visibleAction(),condition.equals("vehicle_color")?null:attributes.vehicleColor());
        return new SearchPlan(condition.equals("target")?"unknown":plan.targetType(),condition.equals("time")?null:plan.start(),condition.equals("time")?null:plan.end(),
                condition.equals("location")?null:plan.locationText(),condition.equals("cameras")?List.of():plan.cameraIds(),attributes,removeTerm(plan.textTerms(),condition,"text:"),plan.clarifications(),condition.equals("plate")?null:plan.plateNumber());
    }
}
