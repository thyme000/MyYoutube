package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日全量重建 ItemCF/Swing 相似度。
 */
@Component
@RequiredArgsConstructor
public class CfScheduleJob {

    private final IItemSimilarityService itemSimilarityService;
    private final ScheduleRoundLock roundLock;

    /**
     * 每日凌晨 3:40 全量重建(避开整点拥挤)
     */
    @Scheduled(cron = "0 40 3 * * ?")
    public void rebuildSimilarity() {
        roundLock.run(VideoScheduleLocks.CF_REBUILD, itemSimilarityService::rebuildAll);
    }
}
