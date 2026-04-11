package cn.diaojiahong.myyoutube.chat.mongo.enums;

import lombok.Getter;

/**
 * 聊天事件类型枚举
 */
@Getter
public enum ChatEventType {

    /**
     * 发送消息
     */
    SEND("send", "发送消息"),

    /**
     * 撤回消息
     */
    WITHDRAW("withdraw", "撤回消息");

    /**
     * 事件类型值
     */
    private final String code;

    /**
     * 事件类型描述
     */
    private final String description;

    ChatEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
