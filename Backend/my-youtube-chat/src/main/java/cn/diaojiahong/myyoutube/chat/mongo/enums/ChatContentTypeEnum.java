package cn.diaojiahong.myyoutube.chat.mongo.enums;

import lombok.Getter;

/**
 * 聊天消息内容类型枚举
 */
@Getter
public enum ChatContentTypeEnum {

    /**
     * 文本消息
     */
    TEXT("text", "文本消息"),

    /**
     * 图片消息
     */
    IMAGE("image", "图片消息"),

    /**
     * 文件消息
     */
    FILE("file", "文件消息"),

    /**
     * 系统消息
     */
    SYSTEM("system", "系统消息"),

    /**
     * 转发视频消息
     */
    FORWARD_VIDEO("forward_video", "转发视频消息"),

    /**
     * 转发动态消息
     */
    FORWARD_DYNAMIC("forward_dynamic", "转发动态消息");

    /**
     * 类型值
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    ChatContentTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 判断是否为系统消息
     */
    public boolean isSystem() {
        return this == SYSTEM;
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
     * 判断是否为转发消息(视频/动态)
     * @return
     */
    public boolean isForward() {
        return this == FORWARD_VIDEO || this == FORWARD_DYNAMIC;
    }
}
