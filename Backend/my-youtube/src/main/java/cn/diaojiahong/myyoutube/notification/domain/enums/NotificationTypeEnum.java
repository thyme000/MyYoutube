package cn.diaojiahong.myyoutube.notification.domain.enums;
import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 用户通知类型枚举
 */
@Getter
public enum NotificationTypeEnum {

    /**
     * 评论点赞
     */
    COMMENT_LIKE("COMMENT_LIKE", "评论点赞"),

    /**
     * 评论回复
     */
    COMMENT_REPLY("COMMENT_REPLY", "评论回复"),

    /**
     * 视频评论
     */
    VIDEO_COMMENT("VIDEO_COMMENT", "视频评论"),

    /**
     * 视频点赞
     */
    VIDEO_LIKE("VIDEO_LIKE", "视频点赞"),

    /**
     * 视频收藏
     */
    VIDEO_COLLECT("VIDEO_COLLECT", "视频收藏"),

    /**
     * 新增关注
     */
    NEW_FOLLOW("NEW_FOLLOW", "新增关注"),

    /**
     * 系统通知
     */
    SYSTEM_NOTICE("SYSTEM_NOTICE", "系统通知");

    /**
     * 类型值
     */
    @EnumValue
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    NotificationTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

}
