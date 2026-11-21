package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

@Getter
public enum ChatEventType {

    SEND("send", "发送消息"),

    WITHDRAW("withdraw", "撤回消息");

    private final String code;

    private final String description;

    ChatEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
