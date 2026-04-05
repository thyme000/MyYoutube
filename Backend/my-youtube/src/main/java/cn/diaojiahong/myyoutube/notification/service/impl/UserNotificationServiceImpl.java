package cn.diaojiahong.myyoutube.notification.service.impl;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationVO;
import cn.diaojiahong.myyoutube.notification.mapper.UserNotificationMapper;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 * 用户站内通知（含评论赞、回复等） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-04-04
 */
@Service
@RequiredArgsConstructor
public class UserNotificationServiceImpl extends ServiceImpl<UserNotificationMapper, UserNotification> implements IUserNotificationService {

    private final IUserService userService;
    private final IVideoCommentService videoCommentService;
    private final AliOssUtil aliOssUtil;

    @Override
    public Result<PageResult<NotificationVO>> getNotificationList(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<UserNotification> page = lambdaQuery()
                .eq(UserNotification::getReceiverId, userId)
                .orderByDesc(UserNotification::getCreateTime)
                .page(pageQuery.toPage());
        List<UserNotification> records = page.getRecords();
        if (records == null) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        List<NotificationVO> result = new ArrayList<>(records.size());
        // 转vo
        records.forEach(r -> {
            NotificationVO vo = new NotificationVO();
            vo.setId(r.getId());
            vo.setSenderId(r.getSenderId());
            vo.setType(NotificationTypeEnum.valueOf(r.getType()));
            if (r.getType().equals(NotificationTypeEnum.VIDEO_COMMENT.getCode())) {
                // 存库时 refId 即为视频 id（非评论 id）
                vo.setVideoId(r.getRefId());
            } else if (r.getType().equals(NotificationTypeEnum.COMMENT_LIKE.getCode()) ||
                    r.getType().equals(NotificationTypeEnum.COMMENT_REPLY.getCode())) {
                VideoComment comment = videoCommentService.getById(r.getRefId());
                if (comment != null && !comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())) {
                    vo.setVideoId(comment.getVideoId());
                }
            }
            vo.setContent(r.getContent());
            vo.setRefId(r.getRefId());
            vo.setCreateTime(r.getCreateTime());
            vo.setIsRead(r.getIsRead().equals(1));
            result.add(vo);
        });

        // 填充发送者昵称
        List<Long> senderIds = result.stream().map(NotificationVO::getSenderId).toList();
        Map<Long, String> nicknames = userService.getAuthorNicknames(senderIds);
        result.forEach(r -> {
            r.setSenderNickname(nicknames.getOrDefault(r.getSenderId(), ""));
        });
        // 填充发送者头像url
        List<Long> senderAvatarIds = senderIds;
        Map<Long, String> avatarUrls = userService.getAuthorAvatarUrls(senderAvatarIds);
        result.forEach(r -> {
            r.setSenderAvatarUrl(aliOssUtil.signGetUrl(avatarUrls.getOrDefault(r.getSenderId(), ""), 300));
        });

        return Result.success(PageResult.of(page.getTotal(), page.getPages(), result));
    }

    @Override
    public void readNotification(Long notificationId) {
        UserNotification notification = getById(notificationId);
        Long userId = getUserId();
        if (!Objects.equals(notification.getReceiverId(), userId)) {
            throw new BizException(BizException.NOTIFICATION_ERROR,"不能读取他人通知");
        }
        // 是自己的通知,可以正常读取
        notification.setIsRead(1);
        updateById(notification);
    }

    @Override
    public Result<Long> getUnreadCount() {
        Long userId = getUserId();
        Long count = lambdaQuery()
                .eq(UserNotification::getReceiverId, userId)
                .eq(UserNotification::getIsRead, 0).count();
        return Result.success(count);
    }

    @Override
    public Result<Void> deleteNotification(Long notificationId) {
        boolean success = lambdaUpdate().eq(UserNotification::getId, notificationId)
                .eq(UserNotification::getReceiverId, getUserId())
                .remove();
        return success ? Result.success() : Result.fail("删除失败");
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
