package cn.diaojiahong.myyoutube.content.domain.dto;

import cn.diaojiahong.myyoutube.common.page.CursorCodec;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import java.time.LocalDateTime;
import java.util.Objects;

public record AuthorContentCursor(
        long authorId,
        Long viewerId,
        ContentTypeEnum type,
        LocalDateTime createTime,
        long contentId) {
    private static final CursorCodec<AuthorContentCursor> CODEC = new CursorCodec<>(
            AuthorContentCursor.class, 1024, "作者作品游标无效，请重新加载");

    public String encode() {
        return CODEC.encode(this);
    }

    public static AuthorContentCursor decode(String value, long author, Long viewer, ContentTypeEnum type) {
        return CODEC.decode(value, cursor -> {
            if (cursor.authorId() != author || !Objects.equals(cursor.viewerId(), viewer)
                    || cursor.type() != type || cursor.contentId() < 1 || cursor.createTime() == null)
                throw new IllegalArgumentException();
        });
    }
}
