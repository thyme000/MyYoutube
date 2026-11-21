package cn.diaojiahong.myyoutube.chat.domain;

/**
 * 会话列表上的一行摘要。正文仍完整存在消息里，这里只留列表能放下的长度。
 */
public final class MessagePreview {

    public static final int MAX_LENGTH = 40;

    private MessagePreview() {
    }

    public static String clip(String preview) {
        if (preview == null || preview.isEmpty()) {
            return preview;
        }
        if (preview.codePointCount(0, preview.length()) <= MAX_LENGTH) {
            return preview;
        }
        int end = preview.offsetByCodePoints(0, MAX_LENGTH);
        return preview.substring(0, end);
    }
}
