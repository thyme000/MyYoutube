package cn.diaojiahong.myyoutube.notification.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 用户站内通知（含评论赞、回复等） 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-04-04
 */
public interface IUserNotificationService extends IService<UserNotification> {

    Result<PageResult<NotificationVO>> getNotificationList(PageQuery pageQuery);

    void readNotification(Long notificationId);

    Result<Long> getUnreadCount();

    Result<Void> deleteNotification(Long notificationId);
}
