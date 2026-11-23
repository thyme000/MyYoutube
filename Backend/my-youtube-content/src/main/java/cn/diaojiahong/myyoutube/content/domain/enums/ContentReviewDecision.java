package cn.diaojiahong.myyoutube.content.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ContentReviewDecision {
    PASS, REJECT, UNCERTAIN, BAN, ERROR;

    @EnumValue
    private final String code = name();
}
