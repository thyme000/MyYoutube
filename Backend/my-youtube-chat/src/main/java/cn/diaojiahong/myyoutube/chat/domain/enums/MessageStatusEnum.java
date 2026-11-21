package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

@Getter
public enum MessageStatusEnum {

    SENT("sent", "已发送"),

    DELIVERED("delivered", "已送达"),

    READ("read", "已读"),

    WITHDRAWN("withdrawn", "已撤回");

    private final String code;

    private final String description;

    MessageStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

}