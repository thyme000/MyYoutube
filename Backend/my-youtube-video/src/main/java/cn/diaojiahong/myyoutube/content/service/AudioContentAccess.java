package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/** 读取时立即拦截；MQ 负责异步封禁落库和通知，失败由低频补扫恢复。 */
@Service
@RequiredArgsConstructor
public class AudioContentAccess {
    private final AudioMapper audios;
    private final AfterCommitNotifications notifications;

    public Set<Long> blocked(List<Content> contents) {
        Map<Long, Content> references = contents.stream()
                .filter(c -> c.getAudioId() != null && c.getStatus() != ContentStatusEnum.BANNED)
                .collect(Collectors.toMap(Content::getId, c -> c, (first, duplicate) -> first));
        List<Long> audioIds = references.values().stream().map(Content::getAudioId).distinct().toList();
        if (audioIds.isEmpty()) return Set.of();
        Map<Long, Audio> current = audios.selectBatchIds(audioIds)
                .stream()
                .collect(Collectors.toMap(Audio::getId, a -> a));
        var blocked = new HashSet<Long>();
        for (Content reference : references.values()) {
            Audio audio = current.get(reference.getAudioId());
            if (audio == null || audio.getReviewStatus() == AudioReviewStatus.BANNED) {
                blocked.add(reference.getId());
                if (audio != null) {
                    notify(reference.getId(), audio);
                }
            }
        }
        return blocked;
    }

    public boolean bannedForPublication(Content content) {
        if (content.getAudioId() == null) return false;
        // 多个作品可同时核验同一音频；封禁的排他写入仍需等待发布事务结束。
        Audio audio = audios.selectOne(new LambdaQueryWrapper<Audio>().eq(Audio::getId, content.getAudioId()).last("FOR SHARE"));
        if (audio == null) return true;
        if (audio.getReviewStatus() != AudioReviewStatus.BANNED) return false;
        notify(content.getId(), audio);
        return true;
    }

    private void notify(Long contentId, Audio audio) {
        var message = new AudioContentBanMessage(contentId, audio.getId(), audio.getIndexVersion());
        notifications.send(MqConstants.TOPIC_AUDIO_CONTENT_BAN, message, MetricNames.AUDIO_CASCADE_NOTIFICATION_FAILED);
    }
}
