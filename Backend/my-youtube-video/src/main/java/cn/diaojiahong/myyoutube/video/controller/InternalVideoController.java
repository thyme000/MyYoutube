package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.share.service.IContentShareService;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * video 服务内部接口: 供 chat 等服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalVideoController {

    private final cn.diaojiahong.myyoutube.recommend.service.IContentTagService contentTags;

    private final IContentService contentService;
    private final ContentUnderstandingMapper understandings;
    private final cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper likes;
    private final cn.diaojiahong.myyoutube.content.service.ContentAccessService access;
    private final IContentShareService contentShares;

    private final NotificationPublisher notificationPublisher;
    private final IUserTagPreferenceService userTagPreferenceService;

    @GetMapping("/content/search-document/{contentId}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentDocMessage searchDocument(@PathVariable Long contentId) {
        Content content = contentService.getById(contentId);
        if (content == null) return null;
        if (Integer.valueOf(1).equals(content.getIsDelete()) || access.hasBannedAudio(content))
            return ContentDocMessage.builder().contentId(contentId)
                    .indexVersion(content.getIndexVersion()).deleted(true).build();
        List<Long> tagIds = contentTags.lambdaQuery()
                .eq(cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag::getContentId, contentId)
                .list().stream().map(cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag::getTagId).toList();
        ContentUnderstanding understanding = understandings.selectById(contentId);
        return cn.diaojiahong.myyoutube.content.support.ContentDocMessages.of(content, tagIds,
                understanding == null ? null : understanding.getSummary());
    }

    @GetMapping("/content/forward-info/{contentId}")
    public ContentInternalPort.ForwardInfo forwardInfo(@PathVariable Long contentId, @RequestParam Long viewerId) {
        Content content = access.readable(contentId, viewerId);
        return new ContentInternalPort.ForwardInfo(
                contentId,
                content.getType().getCode(),
                content.getTitle(),
                content.getCoverObjectKey());
    }

    @PostMapping("/content/share/{contentId}")
    public boolean incrementShareCount(@PathVariable Long contentId, @RequestParam Long viewerId) {
        contentShares.record(contentId, viewerId);
        return true;
    }

    @PostMapping("/content/liked-map")
    public Map<Long, Boolean> likedMap(@RequestParam Long userId, @RequestBody Set<Long> contentIds) {
        if (contentIds.isEmpty()) return Map.of();
        return likes.selectList(new LambdaQueryWrapper<ContentLike>()
                .eq(ContentLike::getUserId, userId)
                .in(ContentLike::getContentId, contentIds)).stream()
                .collect(Collectors.toMap(ContentLike::getContentId, row -> true));
    }

    @GetMapping("/preference/top")
    public List<PreferencePort.TagPreferenceSummary> topPreferences(@RequestParam("userId") Long userId,
                                                                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return userTagPreferenceService.lambdaQuery()
                .eq(UserTagPreference::getUserId, userId)
                .gt(UserTagPreference::getScore, 0)
                .orderByDesc(UserTagPreference::getScore)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)))
                .list().stream()
                .map(t -> new PreferencePort.TagPreferenceSummary(
                        t.getTagId(),
                        t.getScore().doubleValue()))
                .toList();
    }

    @PostMapping("/notification/create")
    public void createNotification(@RequestBody NotificationPort.NotificationCreate create) {
        UserNotification n = new UserNotification();
        n.setReceiverId(create.getReceiverId());
        n.setSenderId(create.getSenderId());
        n.setType(create.getType());
        n.setContent(create.getContent());
        n.setRefType(create.getRefType());
        n.setRefId(create.getRefId());
        n.setExtraJson(create.getExtraJson());
        // chat 经 Feign 进来的通知同样补门铃
        notificationPublisher.publish(n);
    }
}
