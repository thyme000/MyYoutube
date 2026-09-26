package cn.diaojiahong.myyoutube.video.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum VideoVisibilityEnum {
    /** 公开视频 */
    PUBLIC("public", "公开"),

    /** 私有视频 */
    PRIVATE("private", "私有"),

    /** (对部分人群)不公开视频 */
    UNLISTED("unlisted", "不公开");

    /** 可见性代码 */
    @EnumValue
    private final String code;

    /** 可见性名称 */
    private final String name;

    VideoVisibilityEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 根据代码获取枚举
     */
    public static VideoVisibilityEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (VideoVisibilityEnum visibility : values()) {
            if (visibility.getCode().equals(code)) {
                return visibility;
            }
        }
        return null;
    }

    /**
     * 根据名称获取枚举
     */
    public static VideoVisibilityEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (VideoVisibilityEnum visibility : values()) {
            if (visibility.getName().equals(name)) {
                return visibility;
            }
        }
        return null;
    }
}
