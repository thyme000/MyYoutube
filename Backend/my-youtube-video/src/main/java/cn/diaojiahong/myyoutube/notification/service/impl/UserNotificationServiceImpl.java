package cn.diaojiahong.myyoutube.notification.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.video.api.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationVO;
import cn.diaojiahong.myyoutube.notification.mapper.UserNotificationMapper;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.notification.support.ReviewFeedback;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
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
public class UserNotificationServiceImpl extends ServiceImpl<UserNotificationMapper,
UserNotification> implements IUserNotificationService {
    private final ContentAccessService access;

    private final UserPort userPort;
    private final IContentCommentService comments;

    @Override
    public PageResult<NotificationVO> getNotificationList(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<UserNotification> page = lambdaQuery()
                .eq(UserNotification::getReceiverId, userId)
                .orderByDesc(UserNotification::getCreateTime)
                .page(pageQuery.toPage());
        List<UserNotification> records = page.getRecords();
        if (records == null) {

            return PageResult.of(0, 0, List.of());
        }

        List<NotificationVO> result = new ArrayList<>(records.size());
        // 转vo
        records.forEach(r -> {
            NotificationVO vo = new NotificationVO();
            vo.setId(r.getId());
            vo.setSenderId(r.getSenderId());
            vo.setType(NotificationTypeEnum.valueOf(r.getType()));
            vo.setRefType(r.getRefType());
            Long contentId = null;
            if (RefTypeEnum.CONTENT.getCode().equals(r.getRefType())) contentId = r.getRefId();
            else if (RefTypeEnum.COMMENT.getCode().equals(r.getRefType())) {
                ContentComment comment = comments.getById(r.getRefId());
                if (comment != null && !CommentStatusEnum.DELETED.getCode().equals(comment.getStatus()))
                    contentId = comment.getContentId();
            }
            if (contentId != null) {
                try {
                    Content current = access.readable(contentId, userId);
                    vo.setContentId(current.getId());
                    vo.setMediaType(current.getType().getCode());
                } catch (BizException ignored) {
                    // 通知保留，失效或已无权访问的内容不提供跳转目标。
                }
            }
            vo.setContent(ReviewFeedback.notificationContent(r));
            vo.setRefId(r.getRefId());
            vo.setCreateTime(r.getCreateTime());
            vo.setIsRead(r.getIsRead().equals(1));
            result.add(vo);
        });

        // 填充发送者昵称
        List<Long> senderIds = result.stream().map(NotificationVO::getSenderId).toList();
        List<UserSummary> senders = userPort.listByIds(senderIds);
        Map<Long, String> nicknames = UserSummaries.toNicknameMap(senders);
        result.forEach(r -> {
            r.setSenderNickname(r.getSenderId() != null ? nicknames.getOrDefault(r.getSenderId(), "") : "");
        });

        return PageResult.of(page.getTotal(), page.getPages(), result);
    }

    @Override
    public void readNotification(Long notificationId) {

        UserNotification notification = getById(notificationId);
        Long userId = getUserId();
        if (notification == null || !Objects.equals(notification.getReceiverId(), userId)) {

            throw new BizException(ErrorCode.FORBIDDEN, "不能读取他人通知");
        }

        // 是自己的通知,可以正常读取
        notification.setIsRead(1);
        updateById(notification);
    }

    @Override
    public Long getUnreadCount() {
        Long userId = getUserId();
        Long count = lambdaQuery()
                .eq(UserNotification::getReceiverId, userId)
                .eq(UserNotification::getIsRead, 0).count();
        return count;
    }

    @Override
    public void deleteNotification(Long notificationId) {
        boolean success = lambdaUpdate().eq(UserNotification::getId, notificationId)
                .eq(UserNotification::getReceiverId, getUserId())
                .remove();
        if (!success) {

            throw new BizException(ErrorCode.STATE_CONFLICT, "删除失败");
        }
    }

    private Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.valueOf(userId);
    }
}
