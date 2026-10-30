package cn.diaojiahong.myyoutube.common.media;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum PipelineStatus {
    PROCESSING, COMPLETED, WAITING_ARTIFACTS;

    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
