package cn.diaojiahong.myyoutube.collect.service;

import cn.diaojiahong.myyoutube.collect.domain.po.ContentCollect;
import cn.diaojiahong.myyoutube.collect.domain.vo.CollectVO;
import cn.diaojiahong.myyoutube.collect.mapper.ContentCollectMapper;
import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 内容收藏 服务实现: 装配链与赞过列表同构(摘要端口跨类型 + 作者/封面签名补全)
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
@Service
@RequiredArgsConstructor
public class ContentCollectServiceImpl extends ServiceImpl<ContentCollectMapper, ContentCollect> implements IContentCollectService {

    private static final int STORAGE_EXPIRE = 24 * 60 * 60;

    private final ContentCollectMapper collectMapper;
    private final ContentSummaryPort contentSummaryPort;
    private final IContentService contentService;
    private final UserPort userPort;
    private final IUserFollowService userFollowService;
    private final S3StorageUtil storageUtil;
    private final CounterBuffer counterBuffer;
    private final IUserNotificationService userNotificationService;
    private final NotificationPublisher notificationPublisher;
    private final PushFanoutPublisher pushFanoutPublisher;

    @Override
    public void collect(Long contentId) {
        Long userId = getUserId();
        if (contentService.getById(contentId) == null) {

            throw new BizException(BizException.BIZ_ERROR, "内容不存在");
        }
        Content content = contentService.getById(contentId);
        if (content == null) {

            throw new BizException(BizException.BIZ_ERROR, "内容不存在");
        }
        if (lambdaQuery().eq(ContentCollect::getUserId, userId).eq(ContentCollect::getContentId, contentId).exists()) {

            return;
        }
        save(new ContentCollect().setUserId(userId).setContentId(contentId).setCreateTime(LocalDateTime.now()));
        counterBuffer.add(CounterType.CONTENT_COLLECT, contentId, 1);
        notifyAuthor(userId, content);
        return;
    }

    /** 收藏通知: 给作者落一条站内通知并发门铃信号, 自己收藏自己不发 */
    private void notifyAuthor(Long userId, Content content) {

        Long authorId = content.getAuthorId();
        if (authorId == null || authorId.equals(userId)) {

            return;
        }
        // 落库 + 门铃与降级语义都在门面里, 这里不再自包 try/catch
        UserNotification notification = new UserNotification();
        notification.setReceiverId(authorId);
        notification.setSenderId(userId);
        notification.setType(NotificationTypeEnum.CONTENT_COLLECT.getCode());
        notification.setRefType(content.getType() == ContentTypeEnum.DYNAMIC
                ? RefTypeEnum.DYNAMIC.getCode() : RefTypeEnum.VIDEO.getCode());
        notification.setRefId(content.getId());
        notificationPublisher.publish(notification);
    }

    @Override
    public void cancelCollect(Long contentId) {
        Long userId = getUserId();
        // 只有行存在才减计数, 防重复取消把计数减穿
        boolean removed = lambdaUpdate()
                .eq(ContentCollect::getUserId, userId)
                .eq(ContentCollect::getContentId, contentId)
                .remove();
        if (removed) {

            counterBuffer.add(CounterType.CONTENT_COLLECT, contentId, -1);
        }
        return;
    }

    @Override
    public PageResult<CollectVO> page(PageQuery pageQuery) {
        Long userId = getUserId();
        int offset = (pageQuery.getPageNo() - 1) * pageQuery.getPageSize();
        List<Long> contentIds = collectMapper.selectCollectIds(userId, offset, pageQuery.getPageSize());
        if (contentIds.isEmpty()) {

            return PageResult.of(0, 0, List.of());
        }

        // toList 保序: 摘要装配按入参顺序返回, 即收藏时间倒序
        List<ContentSummary> summaries = contentSummaryPort.listByIdsOrdered(
                contentIds.stream().map(String::valueOf).toList());
        if (summaries.isEmpty()) {

            return PageResult.of(0, 0, List.of());
        }

        Map<Long, LocalDateTime> collectTimeById = lambdaQuery()
                .eq(ContentCollect::getUserId, userId)
                .in(ContentCollect::getContentId, contentIds)
                .list().stream()
                .collect(Collectors.toMap(ContentCollect::getContentId, ContentCollect::getCreateTime));

        List<CollectVO> voList = summaries.stream().map(s -> {
            CollectVO vo = BeanUtil.toBean(s, CollectVO.class);
            vo.setId(String.valueOf(s.getId()));
            vo.setCollectTime(collectTimeById.get(s.getId()));
            return vo;
        }).toList();

        List<Long> authorIds = voList.stream().map(CollectVO::getAuthorId).toList();
        List<UserSummary> authors = userPort.listByIds(authorIds);
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(authors);
        List<Boolean> isFollows = userFollowService.isFollowOnList(authorIds, userId);
        for (int i = 0; i < voList.size(); i++) {

            CollectVO vo = voList.get(i);
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
            vo.setIsFollow(isFollows.get(i));
        }

        int total = collectMapper.selectCollectCount(userId);
        int totalPages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        return PageResult.of(total, totalPages, voList);
    }

    @Override
    public Boolean status(Long contentId) {
        Long userId = getUserId();
        boolean collected = lambdaQuery()
                .eq(ContentCollect::getUserId, userId)
                .eq(ContentCollect::getContentId, contentId)
                .exists();
        return collected;
    }

    private Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
