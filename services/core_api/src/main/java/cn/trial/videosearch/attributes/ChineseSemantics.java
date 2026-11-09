package cn.trial.videosearch.attributes;

import java.util.List;
import java.util.Map;

/** Language validation never changes original evidence text or protocol identifiers. */
public final class ChineseSemantics {
    private ChineseSemantics(){}
    public static void requireText(String value){
        if(value==null)return;
        long chinese=value.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).count();
        long latin=value.chars().filter(c->c>='A'&&c<='Z'||c>='a'&&c<='z').count();
        if(chinese==0||latin>Math.max(12,chinese*2))throw new IllegalArgumentException("识别语义必须使用中文");
    }
    public static void requireAttributes(Map<String,Object> attributes){
        if(attributes==null)return;
        for(var value:attributes.values()){
            if(value instanceof String text)requireText(text);
            else if(value instanceof List<?> entries)for(var entry:entries)if(entry instanceof String text)requireText(text);
        }
    }
}
