package cn.diaojiahong.myyoutube.video.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AudioReviewStatus {
    PENDING, APPROVED, REJECTED, BANNED;

    @EnumValue @JsonValue
    private final String code = name().toLowerCase(java.util.Locale.ROOT);

    public String getCode() {
        return code;
    }
}
