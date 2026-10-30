package cn.diaojiahong.myyoutube.transcode.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum TranscodeTaskStatus {
    PENDING, PROCESSING, DONE, FAILED, EXPIRED;

    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
