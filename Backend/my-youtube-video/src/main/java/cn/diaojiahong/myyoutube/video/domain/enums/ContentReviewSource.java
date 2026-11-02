package cn.diaojiahong.myyoutube.video.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ContentReviewSource {
    AUTO, MANUAL, AUDIO;

    @EnumValue
    private final String code = name();
}
