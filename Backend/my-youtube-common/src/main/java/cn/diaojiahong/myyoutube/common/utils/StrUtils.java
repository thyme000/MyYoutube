package cn.diaojiahong.myyoutube.common.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public class StrUtils {

    private static final int MAX_LEN = 30;

    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    private static final HanyuPinyinOutputFormat PINYIN_FORMAT;

    private static final Pattern SPECIAL_CHAR_PATTERN = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]");

    static {
        PINYIN_FORMAT = new HanyuPinyinOutputFormat();
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    /**
     * 生成标准 URL slug
     * 示例：搞笑视频 → gao-xiao-shi-pin；hello世界 → hello-shi-jie
     * 规则：汉字转拼音；仅保留 ASCII 字母与数字，其余交给第二步统一成连字符。
     */
    public static String generateSlug(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder pinyin = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                try {
                    String[] pinyinArr = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
                    if (pinyinArr != null && pinyinArr.length > 0) {
                        pinyin.append(pinyinArr[0]).append('-');
                    }
                } catch (BadHanyuPinyinOutputFormatCombination ignored) {
                    // 单字无法转拼音时跳过
                }
            } else if (isAsciiLetterOrDigit(c)) {
                pinyin.append(Character.toLowerCase(c));
            }
        }

        String slug = SPECIAL_CHAR_PATTERN.matcher(pinyin.toString())
                .replaceAll("-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);

        return slug.isBlank() ? "" : slug;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    /**
     * 判断是否为中文字符
     */
    private static boolean isChinese(char c) {
        return String.valueOf(c).matches("[\\u4e00-\\u9fa5]");
    }

    /**
     * 标准化显示关键词
     * @param keyword
     * @return
     */
    public static String normalizeDisplayKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }

        String s = keyword.replaceAll("\\p{Cntrl}", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return "";
        }

        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s;
    }

    public static String normalizeDedupKeyword(String keyword) {
        String display = normalizeDisplayKeyword(keyword);
        if (display.isEmpty()) {
            return "";
        }

        String lower = display.toLowerCase(Locale.ROOT);
        String noAccent = Normalizer.normalize(lower, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String dedup = noAccent.replaceAll("[^\\p{IsHan}a-z0-9]", "");
        return dedup.isEmpty() ? lower.replaceAll("\\s+", "") : dedup;
    }
}
