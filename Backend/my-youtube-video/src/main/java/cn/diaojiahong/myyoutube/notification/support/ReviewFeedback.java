package cn.diaojiahong.myyoutube.notification.support;

import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;

public final class ReviewFeedback {
    private ReviewFeedback() {}

    public static String message(String subject, boolean approved) {
        return "您的" + subject + (approved ? "已通过审核。" : "未通过审核，已限制展示。");
    }

    public static String notificationContent(UserNotification notice) {
        if ("SYSTEM_NOTICE".equals(notice.getType()) && "CONTENT".equals(notice.getRefType())) {
            if ("内容审核通过".equals(notice.getTitle())) return message("内容", true);
            if ("内容审核未通过".equals(notice.getTitle())) return message("内容", false);
        }
        return notice.getContent();
    }
}
