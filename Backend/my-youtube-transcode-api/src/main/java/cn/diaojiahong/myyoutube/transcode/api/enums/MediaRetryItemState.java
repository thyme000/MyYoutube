package cn.diaojiahong.myyoutube.transcode.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum MediaRetryItemState {
    PENDING, RUNNING, SUCCESS, FAILED, SKIPPED;

    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
