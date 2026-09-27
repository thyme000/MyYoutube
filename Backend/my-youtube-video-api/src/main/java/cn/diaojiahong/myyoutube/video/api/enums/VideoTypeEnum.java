package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum VideoTypeEnum {

    /**
     * 短视频
     */
    SHORT_VIDEO("short", "短视频"),

    /**
     * 长视频
     */
    LONG_VIDEO("long", "长视频");

    /**
     * 类型代码
     */
    @EnumValue
    private final String code;

    /**
     * 类型名称
     */
    private final String name;

    VideoTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 获取类型代码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取类型名称
     */
    public String getName() {
        return name;
    }

    /**
     * 根据代码获取枚举
     */
    public static VideoTypeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (VideoTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
