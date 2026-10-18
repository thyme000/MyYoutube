package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    private final CounterBuffer counterBuffer;

    private final IUserFollowService userFollowService;
    private final NotificationPublisher notificationPublisher;
    private final IUserTagPreferenceService userTagPreferenceService;

    @GetMapping("/content/search-document/{contentId}")
    public cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage searchDocument(@PathVariable Long contentId) {
        var content = contentService.getById(contentId);
        if (content == null || Integer.valueOf(1).equals(content.getIsDelete())) return null;
        var tagIds = contentTags.lambdaQuery().eq(cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag::getContentId, contentId)
                .list().stream().map(cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag::getTagId).toList();
        var understanding = understandings.selectById(contentId);
        return cn.diaojiahong.myyoutube.content.support.ContentDocMessages.of(content, tagIds,
                understanding == null ? null : understanding.getSummary());
    }
    @GetMapping("/content/forward-info/{contentId}")
    public ContentInternalPort.ForwardInfo forwardInfo(@PathVariable Long contentId, @RequestParam Long viewerId) {
        var content = access.readable(contentId, viewerId);
        return new ContentInternalPort.ForwardInfo(contentId, content.getType().getCode(), content.getTitle(), content.getCoverObjectKey());
    }
    @PostMapping("/content/share/{contentId}")
    public boolean incrementShareCount(@PathVariable Long contentId, @RequestParam Long viewerId) {
        access.readable(contentId, viewerId);
        counterBuffer.add(CounterType.CONTENT_SHARE, contentId, 1);
        return true;
    }

    @PostMapping("/content/liked-map")
    public Map<Long, Boolean> likedMap(@RequestParam Long userId, @RequestBody java.util.Set<Long> contentIds) {
        if (contentIds.isEmpty()) return Map.of();
        return likes.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<cn.diaojiahong.myyoutube.like.domain.po.ContentLike>()
                .eq(cn.diaojiahong.myyoutube.like.domain.po.ContentLike::getUserId, userId)
                .eq(cn.diaojiahong.myyoutube.like.domain.po.ContentLike::getIsLiked, 1)
                .in(cn.diaojiahong.myyoutube.like.domain.po.ContentLike::getContentId, contentIds)).stream()
                .collect(java.util.stream.Collectors.toMap(cn.diaojiahong.myyoutube.like.domain.po.ContentLike::getContentId, row -> true));
    }
    @GetMapping("/follow/follow-ids")
    public List<Long> getFollowIds(@RequestParam("userId") Long userId) {
        return userFollowService.getFollowIds(userId);
    }

    @GetMapping("/preference/top")
    public List<PreferencePort.TagPreferenceSummary> topPreferences(@RequestParam("userId") Long userId,
                                                                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return userTagPreferenceService.lambdaQuery()
                .eq(cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference::getUserId, userId)
                .orderByDesc(cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference::getScore)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)))
                .list().stream()
                .map(t -> new PreferencePort.TagPreferenceSummary(t.getTagId(), t.getScore() != null ? t.getScore() : 0.0))
                .toList();
    }

    @PostMapping("/follow/interrelated")
    public boolean interrelated(@RequestBody FollowPort.InterrelatedQuery query) {
        return userFollowService.interrelated(query.getUserId(), query.getMemberIds());
    }

    @GetMapping("/follow/fans-count")
    public Long fansCount(@RequestParam("userId") Long userId) {
        return userFollowService.countFans(userId);
    }

    @GetMapping("/follow/attention-count")
    public Long attentionCount(@RequestParam("userId") Long userId) {
        return userFollowService.countAttention(userId);
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
        // chat 经 Feign 进来的通知同样补门铃(原先只落库, chat 侧自行补发, 现统一收口)
        notificationPublisher.publish(n);
    }
}
