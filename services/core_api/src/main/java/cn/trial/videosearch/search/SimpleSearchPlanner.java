package cn.trial.videosearch.search;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Complete, bounded phrases only. Unrecognized conditions go to the NLP model. */
public final class SimpleSearchPlanner {
    private static final String PREFIX = "(?:请)?(?:查找|搜索|查询|找|查一下)?";
    private static final String COLOR = "(黑|白|红|橙|黄|绿|青|蓝|紫|灰|棕|褐|粉|银|金)色?";
    private static final Pattern TARGET = Pattern.compile(PREFIX + "(车辆|车|人|人物|人员)");
    private static final Pattern VEHICLE = Pattern.compile(PREFIX + COLOR + "(?:的)?(?:车辆|车)");
    private static final Pattern UPPER = Pattern.compile(PREFIX + "(?:穿|穿着)?" + COLOR + "(?:的)?(?:上衣|外套)(?:的)?(?:人|人物|人员)");
    private static final Pattern CLOTHING = Pattern.compile(PREFIX + "(?:穿|穿着)" + COLOR + "(?:衣服|衣着|衣)(?:的)?(?:人|人物|人员)");
    private static final Pattern CARRYING=Pattern.compile(PREFIX+"(?:背|背着|带|带着|拿|拿着|提|提着)(?:一个)?(背包|双肩包|手提包)(?:的)?(?:人|人物|人员)");
    private static final Pattern CYCLING=Pattern.compile(PREFIX+"(?:骑|骑着)(自行车|摩托车)(?:的)?(?:人|人物|人员)");
    private SimpleSearchPlanner() {}

    public static Optional<SearchPlan> parse(String input) {
        if (input == null || input.length() > 1000) return Optional.empty();
        // Normalize spacing for complete recognized phrases only. Complex
        // sentences still go to NLP with their original input intact.
        String text = input.strip().replaceAll("[\\p{Z}\\s]+", "");
        var target = TARGET.matcher(text);
        if (target.matches()) return Optional.of(plan(java.util.Set.of("车辆", "车").contains(target.group(1)) ? "vehicle" : "person", null, null, null));
        var vehicle = VEHICLE.matcher(text);
        if (vehicle.matches()) return Optional.of(plan("vehicle", null, null, vehicle.group(1)+"色"));
        var upper = UPPER.matcher(text);
        if (upper.matches()) return Optional.of(plan("person", upper.group(1)+"色", null, null));
        var clothing = CLOTHING.matcher(text);
        if (clothing.matches()) return Optional.of(plan("person", null, clothing.group(1)+"色", null));
        var carrying=CARRYING.matcher(text);
        if(carrying.matches())return Optional.of(new SearchPlan("person",null,null,null,List.of(),new SearchPlan.Attributes(null,null,null,List.of(carrying.group(1)),null,null),List.of(),List.of()));
        var cycling=CYCLING.matcher(text);
        if(cycling.matches())return Optional.of(new SearchPlan("person",null,null,null,List.of(),new SearchPlan.Attributes(null,null,null,List.of(),cycling.group(1),null),List.of(),List.of()));
        return Optional.empty();
    }


    private static SearchPlan plan(String target, String upper, String clothing, String vehicle) {
        return new SearchPlan(target, null, null, null, List.of(),
                new SearchPlan.Attributes(upper, null, clothing, List.of(), null, null, vehicle), List.of(), List.of());
    }
}
