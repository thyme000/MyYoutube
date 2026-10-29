package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.content.mapper.ContentStatisticsMapper;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentPublicationEventMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AudioHistoricalAuthorCountService {
    private static final int BATCH_SIZE = 200;
    private final ContentPublicationEventMapper events;
    private final AudioMapper audios;
    private final ContentStatisticsMapper statistics;
    private final TransactionTemplate transaction;

    public void apply(Long audioId) {
        List<Long> ids = events.pendingAudioCountIds(audioId, BATCH_SIZE);
        if (ids.isEmpty()) return;
        // 先确定本批事件，再读取历史作者；聚合期间不持有音频行锁。
        long historicalAuthors = statistics.historicalAuthorCount(audioId);
        transaction.executeWithoutResult(tx -> {
            // 先写音频，再确认本批事件；并发旧结果不能覆盖更新的人数。
            if (audios.update(null, new LambdaUpdateWrapper<Audio>().eq(Audio::getId, audioId)
                    .setSql("historical_author_count = GREATEST(historical_author_count, {0})", historicalAuthors)) != 1)
                throw new IllegalStateException("音频历史作者数更新失败");
            // 本批事件可能已被并发消费者确认，不要求更新条数等于批次大小。
            events.finishAudioCounts(ids);
            events.completeIfReady(ids);
        });
    }
}
