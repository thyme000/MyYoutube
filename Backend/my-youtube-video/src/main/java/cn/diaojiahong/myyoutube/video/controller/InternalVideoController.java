package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import cn.diaojiahong.myyoutube.video.api.VideoInternalPort;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
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

    private final IVideoService videoService;
    private final DynamicMapper dynamicMapper;
    private final IContentService contentService;
    private final CounterBuffer counterBuffer;
    private final ObjectMapper objectMapper;
    private final IUserFollowService userFollowService;
    private final IUserNotificationService userNotificationService;
    private final IUserTagPreferenceService userTagPreferenceService;

    @GetMapping("/video/forward-info/{videoId}")
    public VideoInternalPort.ForwardVideoInfo getForwardVideo(@PathVariable Long videoId) {
        Video video = videoService.getById(videoId);
        if (video == null || Boolean.TRUE.equals(video.getIsDelete())) {
            return null;
        }
        return new VideoInternalPort.ForwardVideoInfo(video.getId(), video.getTitle(), video.getCoverUrl());
    }

    // Feign 基路径是 /internal/video, 这里必须拼全 /video/dynamic/... 否则 chat 转发动态 404
    @GetMapping("/video/dynamic/forward-info/{dynamicId}")
    public VideoInternalPort.ForwardDynamicInfo getForwardDynamic(@PathVariable Long dynamicId) {
        Dynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null || Integer.valueOf(1).equals(dynamic.getIsDelete())) {
            return null;
        }
        return new VideoInternalPort.ForwardDynamicInfo(dynamic.getId(), dynamic.getTitle(), firstFrameKey(dynamic.getTimelineJson()));
    }

    /** 从时间轴 JSON 里取首帧图片 objectKey 作封面(与 ContentSummaryPortImpl 同口径) */
    private String firstFrameKey(String timelineJson) {
        if (timelineJson == null) {
            return null;
        }
        try {
            List<Map<String, Object>> timeline = objectMapper.readValue(timelineJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (!timeline.isEmpty() && timeline.get(0).get("imageObjectKey") != null) {
                return String.valueOf(timeline.get(0).get("imageObjectKey"));
            }
        } catch (Exception ignored) {
            // 时间轴解析失败时封面留空
        }
        return null;
    }

    @PostMapping("/video/share/{videoId}")
    public boolean incrementShareCount(@PathVariable Long videoId) {
        // 计数缓冲: 转发链路高频小增量, 批量落库
        counterBuffer.add(CounterType.CONTENT_SHARE, videoId, 1);
        return true;
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
        userNotificationService.save(n);
    }
}
