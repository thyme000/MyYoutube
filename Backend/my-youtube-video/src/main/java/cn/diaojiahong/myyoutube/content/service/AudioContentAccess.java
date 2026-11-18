package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

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

    /**
     * 单条引用只按音频自身状态判定，不依赖内容是否可见或已删除。
     * 没有关联音频时返回 false，仅表示不受音频限制；内容本身的访问权限仍需单独校验。
     */
    public boolean isReferenceBlocked(Content content) {
        if (content.getAudioId() == null) {
            return false;
        }

        return isBlocked(content, audios.selectById(content.getAudioId()));
    }

    /** 列表自身处理已封禁内容，此处只补查其余记录，避免重复级联。 */
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
            if (isBlocked(reference, audio)) {
                blocked.add(reference.getId());
            }
        }
        return blocked;
    }

    public boolean bannedForPublication(Content content) {
        if (content.getAudioId() == null) return false;
        // 多个作品可同时核验同一音频；封禁的排他写入仍需等待发布事务结束。
        Audio audio = audios.selectOne(new LambdaQueryWrapper<Audio>().eq(Audio::getId, content.getAudioId()).last("FOR SHARE"));
        return isBlocked(content, audio);
    }

    private boolean isBlocked(Content content, Audio audio) {
        if (audio == null) {
            return true;
        }
        if (audio.getReviewStatus() != AudioReviewStatus.BANNED) {
            return false;
        }

        // 已封禁作品仍返回音频限制结论，只省略重复的级联通知。
        if (content.getStatus() != ContentStatusEnum.BANNED) {
            notify(content.getId(), audio);
        }
        return true;
    }

    private void notify(Long contentId, Audio audio) {
        var message = new AudioContentBanMessage(contentId, audio.getId(), audio.getIndexVersion());
        notifications.send(AudioContentBanMessage.TOPIC, message, MetricNames.AUDIO_CASCADE_NOTIFICATION_FAILED);
    }
}
