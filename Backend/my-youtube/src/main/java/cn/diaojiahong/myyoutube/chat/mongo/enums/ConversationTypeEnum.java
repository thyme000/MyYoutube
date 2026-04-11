package cn.diaojiahong.myyoutube.chat.mongo.enums;

import lombok.Getter;

/**
 * 会话类型枚举
 */
@Getter
public enum ConversationTypeEnum {

    /**
     * 私聊
     */
    SINGLE("single", "私聊"),

    /**
     * 群聊
     */
    GROUP("group", "群聊");

    /**
     * 类型值
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    ConversationTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 判断是否为私聊
     */
    public boolean isSingle() {
        return this == SINGLE;
    }

    /**
     * 判断是否为群聊
     */
    public boolean isGroup() {
        return this == GROUP;
    }
}
