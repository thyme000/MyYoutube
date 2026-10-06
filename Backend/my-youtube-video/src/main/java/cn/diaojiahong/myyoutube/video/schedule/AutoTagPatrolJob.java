package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.recommend.auto.AutoTagMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 机器打标夜巡: 补打无标签或无类目的内容, 自愈 MQ 丢失与 LLM 长故障
 */
@Component
@RequiredArgsConstructor
public class AutoTagPatrolJob {

    private final AutoTagMaintenanceService autoTagMaintenanceService;
    private final ScheduleRoundLock roundLock;

    @Scheduled(cron = "0 20 4 * * ?")
    public void patrol() {
        roundLock.run("schedule:lock:video:auto-tag", autoTagMaintenanceService::patrol);
    }
}
