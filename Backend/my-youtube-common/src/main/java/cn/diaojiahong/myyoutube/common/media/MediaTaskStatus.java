package cn.diaojiahong.myyoutube.common.media;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum MediaTaskStatus {
    PENDING, READY, RUNNING, RETRY_WAIT, SUCCESS, DEAD, BLOCKED;

    @EnumValue
    private final String code = name();

    public String getCode() {
        return code;
    }
}
