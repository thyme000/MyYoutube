package cn.diaojiahong.myyoutube.notification.service;

import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通知发布门面: 落库 + SSE 门铃一次收口。
 * 尽力而为语义在此单点: save 瞬时抖动立即重试 2 次, 仍失败降级不影响主流程;
 * save 成功后门铃丢失不再重试(重试整个 publish 会重复落库), 前端打开通知中心时拉模式兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    /** 门铃信号: 前端收到后拉取通知中心 */
    public static final String NOTIFY_DIRTY_SIGNAL = "NOTIFY_DIRTY";

    private final IUserNotificationService userNotificationService;
    private final PushFanoutPublisher pushFanoutPublisher;

    public void publish(UserNotification notification) {
        for (int attempt = 0; ; attempt++) {
            try {
                userNotificationService.save(notification);
                break;
            } catch (Exception e) {
                if (attempt >= 2) {
                    log.warn("通知落库失败(降级不影响主流程), receiver={}, type={}",
                            notification.getReceiverId(), notification.getType(), e);
                    return;
                }
            }
        }
        try {
            pushFanoutPublisher.pushSseSignal(notification.getReceiverId(), NOTIFY_DIRTY_SIGNAL);
        } catch (Exception e) {
            log.warn("通知门铃发送失败(前端拉模式兜底), receiver={}", notification.getReceiverId(), e);
        }
    }
}
