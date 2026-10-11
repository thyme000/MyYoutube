package cn.diaojiahong.myyoutube.video.api.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 视频状态枚举
 * 对应数据库 status 字段：review(待审核)/published(已发布)/banned(已封禁)
 */
public enum ContentStatusEnum {

    /**
     * 待审核
     */
    REVIEW("review", "待审核"),

    /**
     * 已发布
     */
    PUBLISHED("published", "已发布"),

    /**
     * 已封禁
     */
    BANNED("banned", "已封禁");

    /**
     * 数据库存储的值
     */
    @EnumValue
    private final String code;

    /**
     * 前端展示的描述
     */
    private final String desc;

    ContentStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据 code 获取对应的枚举（用于后端参数转换/校验）
     */
    public static ContentStatusEnum fromCode(String code) {
        for (ContentStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的视频状态码: " + code);
    }
}
