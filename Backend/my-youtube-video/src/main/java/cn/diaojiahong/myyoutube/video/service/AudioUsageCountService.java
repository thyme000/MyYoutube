package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.po.ContentPublicationEvent;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentPublicationEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AudioUsageCountService {
    private static final int BATCH_SIZE = 200;
    private final ContentPublicationEventMapper events;
    private final AudioMapper audios;
    private final TransactionTemplate transaction;

    public void apply(Long audioId) {
        transaction.executeWithoutResult(tx -> {
            // 与发布统一先锁音频，避免事件范围锁和音频锁形成相反的等待顺序。
            Audio audio = audios.selectOne(new LambdaQueryWrapper<Audio>().select(Audio::getId)
                    .eq(Audio::getId, audioId).last("FOR UPDATE"));
            if (audio == null) throw new IllegalStateException("音频不存在，无法更新使用计数");
            List<ContentPublicationEvent> pending = events.pendingAudioCounts(audioId, BATCH_SIZE);
            if (pending.isEmpty()) return;
            long delta = pending.stream().mapToLong(ContentPublicationEvent::getAudioDelta).sum();
            // 计数与这批事件的完成标记同事务提交，重复投递或回滚都不会重复累计。
            if (delta != 0 && audios.update(null, new LambdaUpdateWrapper<Audio>().eq(Audio::getId, audioId)
                    .setSql("usage_count = usage_count + {0}", delta)) != 1)
                throw new IllegalStateException("音频使用计数更新失败");
            List<Long> ids = pending.stream().map(ContentPublicationEvent::getId).toList();
            if (events.finishAudioCounts(ids) != ids.size()) throw new IllegalStateException("音频计数事件完成数量不匹配");
            events.completeIfReady(ids);
        });
    }
}
