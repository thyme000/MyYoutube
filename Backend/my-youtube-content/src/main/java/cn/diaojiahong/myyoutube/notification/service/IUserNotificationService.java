package cn.diaojiahong.myyoutube.notification.service;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IUserNotificationService extends IService<UserNotification> {

    PageResult<NotificationVO> getNotificationList(PageQuery pageQuery);

    void readNotification(Long notificationId);

    Long getUnreadCount();

    void deleteNotification(Long notificationId);
    }
