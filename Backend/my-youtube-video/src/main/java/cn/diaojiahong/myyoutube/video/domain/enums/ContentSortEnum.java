package cn.diaojiahong.myyoutube.video.domain.enums;

import lombok.Getter;

@Getter
public enum ContentSortEnum {
    /** 最新视频 */
    LATEST("latest", "最新"),

    /** 最多点赞视频 */
    MOST_LIKED("mostLiked", "最多点赞"),

    /** 最多分享视频 */
    MOST_SHARED("mostShared", "最多分享"),

    /** 最多观看视频 */
    MOST_PLAYED("mostPlayed", "最多观看"),

    /** 推荐 */
    RECOMMENDED("recommended", "推荐");

    /** 排序字段名 */
    private final String field;

    /** 排序名称 */
    private final String name;

    ContentSortEnum(String field, String name) {
        this.field = field;
        this.name = name;
    }

    /**
     * 根据字段名获取枚举
     */
    public static ContentSortEnum fromField(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        for (ContentSortEnum sort : values()) {
            if (sort.getField().equals(field)) {
                return sort;
            }
        }
        return null;
    }

    /**
     * 根据名称获取枚举
     */
    public static ContentSortEnum fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ContentSortEnum sort : values()) {
            if (sort.getName().equals(name)) {
                return sort;
            }
        }
        return null;
    }

}
