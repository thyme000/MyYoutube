package cn.diaojiahong.myyoutube.video.domain.dto;

import cn.diaojiahong.myyoutube.common.page.CursorCodec;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import java.time.LocalDateTime;

public record AudioContentCursor(long audioId, ContentSortEnum sort, long likeCount, LocalDateTime createTime, long contentId) {
    private static final CursorCodec<AudioContentCursor> CODEC = new CursorCodec<>(
            AudioContentCursor.class, 512, "音频关联内容游标无效，请重新加载");

    public String encode() {
        return CODEC.encode(this);
    }

    public static AudioContentCursor decode(String value, long audioId, ContentSortEnum order) {
        return CODEC.decode(value, decoded -> {
            if (decoded.audioId() != audioId || decoded.sort() != order
                    || decoded.contentId() < 1 || decoded.likeCount() < 0 || decoded.createTime() == null)
                throw new IllegalArgumentException();
        });
    }
}
