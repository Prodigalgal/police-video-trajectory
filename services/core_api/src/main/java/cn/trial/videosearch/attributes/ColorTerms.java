package cn.trial.videosearch.attributes;

import java.util.Locale;

/** Exact aliases only. Composite or unfamiliar colors remain available for review. */
public final class ColorTerms {
    private ColorTerms() {}

    public static String canonical(String value) {
        if (value == null) return null;
        String original = value.strip();
        return switch (original.toLowerCase(Locale.ROOT)) {
            case "white", "白", "白色" -> "白色";
            case "black", "黑", "黑色" -> "黑色";
            case "red", "红", "红色" -> "红色";
            case "blue", "蓝", "蓝色" -> "蓝色";
            case "green", "绿", "绿色" -> "绿色";
            case "yellow", "黄", "黄色" -> "黄色";
            case "grey", "gray", "灰", "灰色" -> "灰色";
            case "silver", "银", "银色" -> "银色";
            case "brown", "棕", "棕色", "褐色" -> "棕色";
            case "orange", "橙", "橙色" -> "橙色";
            case "purple", "紫", "紫色" -> "紫色";
            case "pink", "粉", "粉色" -> "粉色";
            case "gold", "golden", "金", "金色" -> "金色";
            case "beige", "米色" -> "米色";
            case "cyan", "青", "青色" -> "青色";
            case "multicolor", "multicolour", "multi-color", "多色", "花色" -> "多色";
            case "dark", "深色" -> "深色";
            case "light", "浅色" -> "浅色";
            default -> original;
        };
    }
}
