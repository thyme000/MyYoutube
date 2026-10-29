package cn.diaojiahong.myyoutube.video.domain.enums;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;

public enum AudioReviewDecision {
    PASS, REJECT, BAN;

    public static AudioReviewDecision parse(String code) {
        for (AudioReviewDecision decision : values()) {
            if (decision.name().equals(code)) return decision;
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "审核决定或理由无效");
    }
}
