package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 协同过滤离线任务: 每日全量重建 ItemCF/Swing 相似度, 每小时增量刷新新行为涉及的内容
 */
@Slf4j
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
        roundLock.run("schedule:lock:video:cf-rebuild", itemSimilarityService::rebuildAll);
    }

    /**
     * 每小时第 10 分增量刷新
     */
    @Scheduled(cron = "0 10 * * * ?")
    public void refreshSimilarity() {
        roundLock.run("schedule:lock:video:cf-incremental", itemSimilarityService::refreshIncremental);
    }
}
