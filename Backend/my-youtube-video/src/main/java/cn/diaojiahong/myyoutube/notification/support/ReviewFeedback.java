package cn.diaojiahong.myyoutube.notification.support;

import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;

public final class ReviewFeedback {
    public static final String APPROVED_TITLE = "内容审核通过";
    public static final String REJECTED_TITLE = "内容审核未通过";
    public static final String AUDIO_BANNED_TITLE = "内容因配乐封禁受限";
    private ReviewFeedback() {}

    public record ReviewNotice(String title, String content) {}

    public static ReviewNotice reviewNotice(boolean approved, boolean audioBanned) {
        if (approved) return new ReviewNotice(APPROVED_TITLE, message("内容", true));
        if (audioBanned) return new ReviewNotice(AUDIO_BANNED_TITLE, audioBannedMessage());
        return new ReviewNotice(REJECTED_TITLE, message("内容", false));
    }

    public static String message(String subject, boolean approved) {
        return "您的" + subject + (approved ? "已通过审核。" : "未通过审核，已限制展示。");
    }

    public static String audioBannedMessage() {
        return "您的作品因使用的音频被封禁，已限制展示。";
    }

    public static String contentRestrictionReason(boolean audioBanned) {
        return audioBanned ? audioBannedMessage() : message("内容", false);
    }

    public static String notificationContent(UserNotification notice) {
        if ("SYSTEM_NOTICE".equals(notice.getType()) && "CONTENT".equals(notice.getRefType())) {
            if (APPROVED_TITLE.equals(notice.getTitle())) return reviewNotice(true, false).content();
            if (REJECTED_TITLE.equals(notice.getTitle())) return reviewNotice(false, false).content();
            if (AUDIO_BANNED_TITLE.equals(notice.getTitle())) return reviewNotice(false, true).content();
        }
        return notice.getContent();
    }
}
