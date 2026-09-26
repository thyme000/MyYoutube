package cn.diaojiahong.myyoutube.notification.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 关联业务类型枚举
 */
@Getter
public enum RefTypeEnum {

    /**
     * 视频
     */
    VIDEO("VIDEO", "视频"),

    /**
     * 评论
     */
    COMMENT("COMMENT", "评论"),

    /**
     * 系统
     */
    SYSTEM("SYSTEM", "系统"),

    /**
     * 聊天
     */
    CHAT("CHAT", "聊天");

    /**
     * 类型值
     */
    @EnumValue
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    RefTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

}