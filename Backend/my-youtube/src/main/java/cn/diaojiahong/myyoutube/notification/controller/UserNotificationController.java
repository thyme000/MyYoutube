package cn.diaojiahong.myyoutube.notification.controller;


import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationVO;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 用户站内通知（含评论赞、回复等） 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-04-04
 */
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
@Tag(name = "通知模块")
public class UserNotificationController {

    private final IUserNotificationService userNotificationService;

    /**
     * 用户分页查询自己收到的通知
     * @param pageQuery
     * @return
     */
    @Operation(summary = "分页查询用户收到的通知")
    @PutMapping("/list")
    public Result<PageResult<NotificationVO>> getNotificationList(@RequestBody PageQuery pageQuery) {
        return userNotificationService.getNotificationList(pageQuery);
    }

    /**
     * 用户读取某条通知
     * @param notificationId 通知id
     * @return
     */
    @Operation(summary = "用户点击某个通知,设置为已读")
    @PostMapping("/{notificationId}")
    public Result<Void> readNotification(@PathVariable Long notificationId) {
        userNotificationService.readNotification(notificationId);
        return Result.success();
    }

    /**
     * 查询用户一共有多少条未读的通知
     * @return
     */
    @Operation(summary = "查询用户有多少条未读的通知")
    @GetMapping("/unread-count")
    public Result<Long> getUnreadCount() {
        return userNotificationService.getUnreadCount();
    }
}
