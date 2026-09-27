package cn.diaojiahong.myyoutube.video.api.enums;

import lombok.Getter;

/**
 * 评论举报类型枚举
 */
@Getter
public enum CommentReportReasonEnum {

    /**
     * 辱骂
     */
    ABUSE("abuse", "辱骂"),

    /**
     * 涉黄
     */
    PORNOGRAPHY("pornography", "涉黄"),

    /**
     * 广告
     */
    ADVERTISING("advertising", "广告"),

    /**
     * 引战
     */
    TROLLING("trolling", "引战"),

    /**
     * 其他
     */
    OTHER("other", "其他");

    private final String code;
    private final String description;

    CommentReportReasonEnum(String code, String description) {
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

