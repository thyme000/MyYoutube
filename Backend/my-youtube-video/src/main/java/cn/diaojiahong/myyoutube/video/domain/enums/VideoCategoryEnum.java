package cn.diaojiahong.myyoutube.video.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
/**
 * 视频主分类
 */
@Getter
public enum VideoCategoryEnum {

    GAME("GAME", "游戏"),
    MOVIE("MOVIE", "影视"),
    MUSIC("MUSIC", "音乐"),
    KNOWLEDGE("KNOWLEDGE", "知识"),
    LIFE("LIFE", "生活"),
    TECH("TECH", "科技"),
    ENTERTAINMENT("ENTERTAINMENT", "娱乐"),
    OTHER("OTHER", "其他");

    /** 存库 / 接口用，稳定不变 */
    @EnumValue
    private final String code;
    /** 中文展示 */
    private final String label;
    VideoCategoryEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
    public static VideoCategoryEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        for (VideoCategoryEnum c : values()) {
            if (c.code.equalsIgnoreCase(code.trim())) {
                return c;
            }
        }
        return OTHER;
    }
}