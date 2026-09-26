package cn.diaojiahong.myyoutube.chat.mongo.enums;

import lombok.Getter;

/**
 * 消息状态枚举
 */
@Getter
public enum MessageStatusEnum {

    /**
     * 已发送
     */
    SENT("sent", "已发送"),

    /**
     * 已送达
     */
    DELIVERED("delivered", "已送达"),

    /**
     * 已读
     */
    READ("read", "已读"),

    /**
     * 已撤回
     */
    WITHDRAWN("withdrawn", "已撤回");

    /**
     * 状态值
     */
    private final String code;

    /**
     * 状态描述
     */
    private final String description;

    MessageStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

}