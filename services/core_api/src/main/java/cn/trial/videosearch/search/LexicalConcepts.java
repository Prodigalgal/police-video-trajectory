package cn.trial.videosearch.search;

import cn.trial.videosearch.attributes.ColorTerms;
import java.util.*;

/** Controlled alternatives for one concept; separate requested concepts remain conjunctive. */
public final class LexicalConcepts {
    private static final List<List<String>> GROUPS=List.of(
        List.of("白色","white"),List.of("黑色","black"),List.of("红色","red"),List.of("蓝色","blue"),
        List.of("灰色","gray","grey"),List.of("银色","silver"),List.of("黄色","yellow"),List.of("绿色","green"),
        List.of("棕色","brown","褐色"),List.of("橙色","orange"),List.of("紫色","purple"),List.of("粉色","pink"),
        List.of("背包","双肩包","backpack"),List.of("手提包","handbag"),List.of("自行车","bicycle"),
        List.of("摩托车","motorcycle"),List.of("卡车","货车","truck"),List.of("公交车","巴士","bus"),
        List.of("行走","步行","walking"),List.of("奔跑","跑步","running")
    );
    private LexicalConcepts(){}
    public static List<String> alternatives(String text){
        String normalized=ColorTerms.canonical(text);
        for(var group:GROUPS)if(group.stream().anyMatch(value->value.equalsIgnoreCase(normalized)))return group;
        return List.of(text);
    }
}
