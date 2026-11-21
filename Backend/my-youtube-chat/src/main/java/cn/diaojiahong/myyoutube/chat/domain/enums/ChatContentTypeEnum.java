package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

@Getter
public enum ChatContentTypeEnum {

    TEXT("text", "文本消息"),

    IMAGE("image", "图片消息"),

    FILE("file", "文件消息"),

    FORWARD_CONTENT("forward_content", "转发内容消息");

    private final String code;

    private final String description;

    ChatContentTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 判断是否为媒体消息（图片或文件）
     */
    public boolean isMedia() {
        return this == IMAGE || this == FILE;
    }

    /**
     * 判断是否为文本消息
     * @return
     */
    public boolean isText() {
        return this == TEXT;
    }

    /**
     * 判断是否为转发内容消息
     * @return
     */
    public boolean isForward() {
        return this == FORWARD_CONTENT;
    }
}
