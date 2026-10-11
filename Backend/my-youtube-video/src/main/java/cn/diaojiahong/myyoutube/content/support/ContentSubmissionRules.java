package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.exception.BizException;

public final class ContentSubmissionRules {
    private ContentSubmissionRules() {}

    public static String title(String title) {
        if (title == null || title.isBlank() || title.trim().length() > 100)
            throw new BizException(BizException.BIZ_ERROR, "内容标题必须为 1~100 个字符");
        return title.trim();
    }

    public static String description(String description) {
        if (description != null && description.trim().length() > 512)
            throw new BizException(BizException.BIZ_ERROR, "简介不能超过 512 个字符");
        return description == null || description.isBlank() ? null : description.trim();
    }

    public static int allowComment(Integer allow) {
        if (allow != null && allow != 0 && allow != 1)
            throw new BizException(BizException.BIZ_ERROR, "评论开关必须为 0 或 1");
        return allow == null ? 1 : allow;
    }

}
