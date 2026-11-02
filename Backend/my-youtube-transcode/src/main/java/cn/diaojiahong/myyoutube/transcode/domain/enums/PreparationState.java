package cn.diaojiahong.myyoutube.transcode.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum PreparationState {
    ACTIVE, RETIRED;

    @EnumValue
    private final String code = name();
}
