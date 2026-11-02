package cn.diaojiahong.myyoutube.video.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ContentReviewDecision {
    PASS, REJECT, UNCERTAIN, BAN, ERROR;

    @EnumValue
    private final String code = name();
}
