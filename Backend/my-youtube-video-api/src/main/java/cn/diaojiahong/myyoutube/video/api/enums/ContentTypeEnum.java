package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 统一内容池的内容类型。新增内容类型(如纯文字帖)只需在这里注册一个 code。
 */
@Getter
public enum ContentTypeEnum {

    VIDEO("video"),
    DYNAMIC("dynamic");

    @EnumValue
    private final String code;

    ContentTypeEnum(String code) {
        this.code = code;
    }

    public static ContentTypeEnum fromCode(String code) {
        for (ContentTypeEnum type : values()) {
            if (type.getCode().equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
