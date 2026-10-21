package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.text.TextRuleMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HotSearchFilter {
    private static final Pattern REPEATED_DIGITS = Pattern.compile("([0-9])\\1{3,}");

    private final HotSearchProperties properties;
    private final TextRuleMatcher matcher;

    public HotSearchFilter(HotSearchProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        TextRuleMatcher loaded;
        try {
            loaded = TextRuleMatcher.load(properties.getExcludedLocation());
        } catch (IOException | IllegalArgumentException e) {
            log.warn("热搜排除词加载失败，使用空词表，location={}", properties.getExcludedLocation(), e);
            loaded = TextRuleMatcher.empty();
        }
        matcher = loaded;
    }

    public String eligible(String keyword) {
        if (keyword == null) return null;
        String text = keyword.strip();
        if (text.codePointCount(0, text.length()) > properties.getMaxLength()) return null;
        String folded = TextRuleMatcher.normalize(text);
        if (folded.isEmpty() || folded.codePoints().noneMatch(Character::isLetterOrDigit)
                || REPEATED_DIGITS.matcher(folded).matches() || matcher.matches(folded)) return null;
        return text;
    }
}
