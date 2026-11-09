package cn.trial.videosearch.search;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Complete identifiers only. Character substitutions would create false evidence. */
public final class PlateNumbers {
    private static final String PROVINCES="京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼";
    private static final String ALPHABET="A-HJ-NP-Z0-9";
    private static final String LETTER="A-HJ-NP-Z";
    private static final Pattern COMPLETE=Pattern.compile("["+PROVINCES+"]["+LETTER+"](?:["+ALPHABET+"]{5}|[DF]["+ALPHABET+"]{5}|["+ALPHABET+"]{5}[DF]|["+ALPHABET+"]{4}[警学挂港澳])");
    private static final Pattern QUERY=Pattern.compile("["+PROVINCES+"]\\s*["+LETTER+"][\\s·•.－-]*(?:["+ALPHABET+"][\\s·•.－-]*){4,6}["+ALPHABET+"警学挂港澳](?![A-Z0-9])");
    private PlateNumbers(){}
    public static String normalize(String raw){
        if(raw==null||raw.length()>64)throw new IllegalArgumentException("invalid complete plate");
        String value=Normalizer.normalize(raw,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s·•.－-]","");
        if(!COMPLETE.matcher(value).matches())throw new IllegalArgumentException("invalid complete plate");return value;
    }
    public record Query(String number,String remainder,List<String> questions){}
    public static Query extract(String input){
        String value=Normalizer.normalize(input,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);var matcher=QUERY.matcher(value);
        var matches=new ArrayList<String>();int start=-1,end=-1;
        while(matcher.find()){
            try{matches.add(normalize(matcher.group()));if(start<0){start=matcher.start();end=matcher.end();}}
            catch(IllegalArgumentException invalid){return new Query(null,input,List.of("请输入完整且可辨认的车牌号，暂不支持残缺或猜测字符"));}
        }
        if(matches.size()>1)return new Query(null,input,List.of("每次请查询一个完整车牌号"));
        if(!matches.isEmpty()&&value.matches(".*(?:不是|不含|排除|除外|不要|非此|不包括).*"))return new Query(null,input,List.of("暂不支持排除车牌，请输入需要精确查找的完整车牌号"));
        if(matches.isEmpty())return new Query(null,input,value.contains("车牌")||value.contains("牌照")||Pattern.compile("["+PROVINCES+"]\\s*[A-Z][A-Z0-9·•.－\\s?-]{2,}").matcher(value).find()?List.of("请输入完整车牌号，暂不支持残缺车牌匹配"):List.of());
        String rest=(value.substring(0,start)+" "+value.substring(end)).replaceAll("(?:车牌号码?|牌照号码?|车牌|牌照)"," ").strip();
        rest=rest.replaceAll("^(?:请|帮我|给我|查询|查找|搜索|搜|找|查看|检索|一下|所有|为|是|号|的|\\s)+","")
            .replaceAll("(?:的|画面|记录|线索|一下|为|是|号|\\s)+$","").strip();
        return new Query(matches.getFirst(),rest,List.of());
    }
}
