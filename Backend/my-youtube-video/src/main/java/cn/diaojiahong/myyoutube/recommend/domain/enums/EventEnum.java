package cn.diaojiahong.myyoutube.recommend.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum EventEnum {

    /**
     * 播放 2分
     */
    PLAY("play", 2, "内容有效播放达标"),

    /**
     * 完播 4分
     */
    FINISH("finish", 4, "内容达到完播条件"),

    /**
     * 跳过 -2分
     */
    SKIP("skip", -2, "用户跳过内容"),

    /**
     * 踩 -4分
     */
    DISLIKE("dislike", -4, "用户不喜欢内容");

    /**
     * 行为代码
     */
    @EnumValue
    private final String code;

    /**
     * 行为分数
     */
    private final int score;

    /**
     * 行为描述
     */
    private final String description;

    EventEnum(String code, int score, String description) {
        this.code = code;
        this.score = score;
        this.description = description;
    }

    /**
     * 根据代码获取枚举
     */
    public static EventEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }

        for (EventEnum event : values()) {
            if (event.getCode().equals(code)) {
                return event;
            }
        }
        return null;
    }

}
