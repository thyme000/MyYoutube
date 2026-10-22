package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.text.TextRuleMatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public final class ContentSubmissionRules {
    private final TextRuleMatcher matcher;

    public ContentSubmissionRules(@Value("${my-youtube.content-moderation.blocked-location:content-moderation/blocked.txt}") String location) {
        try {
            matcher = TextRuleMatcher.load(location);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("内容拦截词表加载失败，location=" + location, e);
        }
    }

    public String title(String title) {
        if (title == null || title.isBlank() || title.trim().length() > 100)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "内容标题必须为 1~100 个字符");
        check(title, "标题");
        return title.trim();
    }

    public String description(String description) {
        if (description != null && description.trim().length() > 512)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "简介不能超过 512 个字符");
        check(description, "简介");
        return description == null || description.isBlank() ? null : description.trim();
    }

    public int allowComment(Integer allow) {
        if (allow != null && allow != 0 && allow != 1)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "评论开关必须为 0 或 1");
        return allow == null ? 1 : allow;
    }

    private void check(String text, String field) {
        if (matcher.matches(text)) throw new BizException(ErrorCode.INVALID_ARGUMENT, field + "包含不允许的内容，请修改");
    }
}
