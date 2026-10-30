package cn.diaojiahong.myyoutube.transcode.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum MediaRetryBatchStatus {
    QUEUED, RUNNING, COMPLETED, COMPLETED_WITH_FAILURES;

    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
