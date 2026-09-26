package cn.diaojiahong.myyoutube.chat.mongo.enums;

import lombok.Getter;

/**
 * 消息撤回类型枚举
 */
@Getter
public enum RecallTypeEnum {

    /**
     * 发送者撤回
     */
    SENDER("sender", "发送者撤回"),

    /**
     * 管理员撤回
     */
    ADMIN("admin", "管理员撤回");

    /**
     * 类型值
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    RecallTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }


}
