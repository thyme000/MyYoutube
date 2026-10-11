package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * READY 表示媒体准备完成；SUBMITTED 表示已冻结提交，不代表审核通过或发布。
 */
public enum MediaDraftStatusEnum {

    UPLOADING("uploading", "直传中"),
    PROCESSING("processing", "处理中"),
    READY("ready", "可提交"),
    FAILED("failed", "处理失败"),
    SUBMITTED("submitted", "已提交"),
    EXPIRED("expired", "已过期");

    @EnumValue
    private final String code;
    private final String desc;

    MediaDraftStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
