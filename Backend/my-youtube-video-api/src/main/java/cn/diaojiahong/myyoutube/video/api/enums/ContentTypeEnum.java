package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一内容池的内容类型。新增内容类型(如纯文字帖)只需在这里注册一个 code。
 */
@Getter
@AllArgsConstructor
public enum ContentTypeEnum {

    VIDEO("video"),
    DYNAMIC("dynamic");

    @EnumValue
    private final String code;
}
