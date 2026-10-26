package cn.diaojiahong.myyoutube.video.domain.dto;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

public record AudioContentCursor(long audioId, ContentSortEnum sort, long likeCount, LocalDateTime createTime, long contentId) {
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JsonUtil.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    public static AudioContentCursor decode(String value, long audioId, ContentSortEnum order) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            AudioContentCursor decoded = JsonUtil.fromJson(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8), AudioContentCursor.class);
            if (decoded == null || decoded.audioId() != audioId || decoded.sort() != order
                    || decoded.contentId() < 1 || decoded.likeCount() < 0 || decoded.createTime() == null)
                throw new IllegalArgumentException();
            return decoded;
        } catch (RuntimeException invalid) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频关联内容游标无效，请重新加载");
        }
    }
}
