package cn.diaojiahong.myyoutube.video.api.enums;

import lombok.Data;
import lombok.Getter;

/**
 * 评论举报状态枚举
 */
@Getter
public enum CommentReportStatusEnum {

    /**
     * 待处理
     */
    PENDING("PENDING", "待处理"),

    /**
     * 已处理
     */
    PROCESSED("PROCESSED", "已处理");

    private final String code;
    private final String description;

    CommentReportStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
