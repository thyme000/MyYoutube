package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import cn.diaojiahong.myyoutube.video.api.VideoInternalPort;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
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

/**
 * video 服务内部接口: 供 chat 等服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalVideoController {

    private final IVideoService videoService;
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

    @PostMapping("/video/share/{videoId}")
    public boolean incrementShareCount(@PathVariable Long videoId) {
        return videoService.lambdaUpdate().eq(Video::getId, videoId)
                .eq(Video::getIsDelete, false)
                .eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC)
                .eq(Video::getStatus, VideoStatusEnum.PUBLISHED)
                .setIncrBy(Video::getShareCount, 1)
                .update();
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
