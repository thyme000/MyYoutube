package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

@Getter
public enum RecallTypeEnum {

    SENDER("sender", "发送者撤回"),

    ADMIN("admin", "管理员撤回");

    private final String code;

    private final String description;

    RecallTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

}
