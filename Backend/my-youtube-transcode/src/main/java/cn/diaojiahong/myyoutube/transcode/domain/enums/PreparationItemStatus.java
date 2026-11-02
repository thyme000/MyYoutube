package cn.diaojiahong.myyoutube.transcode.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum PreparationItemStatus {
    ALLOCATED, PLANNED, READY, DELETING, DELETED;

    @EnumValue
    private final String code = name();
}
