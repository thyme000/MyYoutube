package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum DraftSubmissionState {
    NONE("none"),
    PENDING("pending"),
    SUBMITTED("submitted"),
    FAILED("failed");

    @EnumValue
    private final String code;

    DraftSubmissionState(String code) { this.code = code; }

}
