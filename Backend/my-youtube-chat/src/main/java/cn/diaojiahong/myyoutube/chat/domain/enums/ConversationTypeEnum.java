package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

@Getter
public enum ConversationTypeEnum {

    SINGLE("single", "私聊"),

    GROUP("group", "群聊");

    private final String code;

    private final String description;

    ConversationTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public boolean isSingle() {
        return this == SINGLE;
    }

    public boolean isGroup() {
        return this == GROUP;
    }
}
