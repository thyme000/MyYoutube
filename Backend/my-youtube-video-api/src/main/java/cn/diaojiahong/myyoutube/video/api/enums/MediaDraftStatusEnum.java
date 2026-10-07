package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 上传草稿状态: UPLOADING(直传中)/PROCESSING(管线处理中)/READY(关键任务完成可发布)/FAILED/EXPIRED(超期清理)
 */
public enum MediaDraftStatusEnum {

    UPLOADING("uploading", "直传中"),
    PROCESSING("processing", "处理中"),
    READY("ready", "待发布"),
    FAILED("failed", "处理失败"),
    PUBLISHED("published", "已发布"),
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
