package cn.diaojiahong.myyoutube.chat.domain.dto;

import cn.diaojiahong.myyoutube.common.page.CursorCodec;

import java.time.LocalDateTime;

/**
 * 会话列表游标。pin、活跃时间、行 id 一起决定顺序; 行 id 可以是成员行也可以是草稿行。
 * userId 和 sendableOnly 写进游标，避免把收件箱游标拿到可发送列表，或拿到别人的列表上。
 */
public record ConversationListCursor(
        long userId,
        int pin,
        LocalDateTime activityAt,
        String rowId,
        boolean sendableOnly) {

    private static final CursorCodec<ConversationListCursor> CODEC = new CursorCodec<>(
            ConversationListCursor.class, 512, "会话列表游标无效，请重新加载");

    public String encode() {
        return CODEC.encode(this);
    }

    public static ConversationListCursor decode(String value, long userId, boolean sendableOnly) {
        return CODEC.decode(value, cursor -> {
            if (cursor.userId() != userId
                    || cursor.sendableOnly() != sendableOnly
                    || (cursor.pin() != 0 && cursor.pin() != 1)
                    || cursor.activityAt() == null
                    || cursor.rowId() == null
                    || cursor.rowId().isBlank()) {
                throw new IllegalArgumentException();
            }
        });
    }
}
