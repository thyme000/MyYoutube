package cn.diaojiahong.myyoutube.recommend.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum EventEnum {

    /**
     * 曝光 1分 //TODO 目前未实现
     */
    EXPOSE("expose", 1, "视频被用户看到"),

    /**
     * 播放 2分
     */
    PLAY("play", 2, "用户开始播放视频"),

    /**
     * 完播 4分
     */
    FINISH("finish", 4, "用户完整观看视频"),

    /**
     * 点赞 4分
     */
    LIKE("like", 4, "用户点赞视频"),

    /**
     * 跳过 -2分
     */
    SKIP("skip", -2, "用户跳过视频"),

    /**
     * 踩 -4分
     */
    DISLIKE("dislike", -4, "用户不喜欢视频");

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