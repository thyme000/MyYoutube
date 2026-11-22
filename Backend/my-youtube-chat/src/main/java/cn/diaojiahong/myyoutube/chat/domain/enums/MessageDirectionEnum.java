package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

@Getter
public enum MessageDirectionEnum {

    BEFORE("向更旧回溯, 省略 cursor 时取最新一页"),

    AFTER("向更新推进, 必须携带 cursor");

    private final String description;

    MessageDirectionEnum(String description) {
        this.description = description;
    }
}
