package cn.diaojiahong.myyoutube.comment.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum CommentStatusEnum {
    /**
     * 正常
     */
    VISIBLE("visible", "正常"),

    /**
     * 用户删除
     */
    DELETED("deleted", "用户删除"),

    /**
     * 违规
     */
    BLOCKED("blocked", "违规");

    /**
     * 状态值
     */
    @EnumValue
    private final String code;

    /**
     * 状态描述
     */
    private final String description;

    CommentStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

}
