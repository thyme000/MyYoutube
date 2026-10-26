package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/** 读取时立即拦截；MQ 负责异步封禁落库和通知，失败由低频补扫恢复。 */
@Service
@RequiredArgsConstructor
public class AudioContentAccess {
    private final DynamicMapper dynamics;
    private final AudioMapper audios;
    private final AfterCommitNotifications notifications;

    public Set<Long> blocked(List<Content> contents) {
        var ids = contents.stream().filter(c -> c.getType() == ContentTypeEnum.DYNAMIC && c.getStatus() != ContentStatusEnum.BANNED)
                .map(Content::getId).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        var references = dynamics.selectBatchIds(ids).stream().filter(d -> d.getAudioId() != null).toList();
        var audioIds = references.stream().map(d -> d.getAudioId()).distinct().toList();
        if (audioIds.isEmpty()) return Set.of();
        var current = audios.selectBatchIds(audioIds).stream().collect(Collectors.toMap(Audio::getId, a -> a));
        var blocked = new HashSet<Long>();
        for (var reference : references) {
            var audio = current.get(reference.getAudioId());
            if (audio == null || audio.getReviewStatus() == AudioReviewStatus.BANNED) {
                blocked.add(reference.getId());
                if (audio != null) notify(reference.getId(), audio);
            }
        }
        return blocked;
    }

    public boolean bannedForPublication(Content content) {
        if (content.getType() != ContentTypeEnum.DYNAMIC) return false;
        var reference = dynamics.selectById(content.getId());
        if (reference == null || reference.getAudioId() == null) return false;
        var audio = audios.selectOne(new LambdaQueryWrapper<Audio>().eq(Audio::getId, reference.getAudioId()).last("FOR UPDATE"));
        if (audio == null) return true;
        if (audio.getReviewStatus() != AudioReviewStatus.BANNED) return false;
        notify(content.getId(), audio);
        return true;
    }

    private void notify(Long contentId, Audio audio) {
        var message = new AudioContentBanMessage(contentId, audio.getId(), audio.getIndexVersion());
        notifications.send(MqConstants.TOPIC_AUDIO_CONTENT_BAN, message, "audio.cascade.notification.failed");
    }
}
