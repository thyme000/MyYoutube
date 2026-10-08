package cn.diaojiahong.myyoutube.comment.domain.enums;

import lombok.Getter;

@Getter
public enum CommentTab {

    HOT("hot"),
    LATEST("latest");

    private final String code;

    CommentTab(String code) {
        this.code = code;
    }

    public static CommentTab of(String code) {
        for (CommentTab tab : values()) {
            if (tab.code.equalsIgnoreCase(code)) {
                return tab;
            }
        }
        throw new IllegalArgumentException("未知评论tab: " + code);
    }
}
