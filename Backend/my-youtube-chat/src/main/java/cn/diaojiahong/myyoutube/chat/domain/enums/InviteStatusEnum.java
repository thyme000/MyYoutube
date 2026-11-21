package cn.diaojiahong.myyoutube.chat.domain.enums;

import lombok.Getter;

/**
 * 群邀请状态。库存枚举名。
 */
@Getter
public enum InviteStatusEnum {

    PENDING("待处理"),

    ACCEPTED("已同意"),

    REJECTED("已拒绝");

    private final String description;

    InviteStatusEnum(String description) {
        this.description = description;
    }
}
