package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.recommend.auto.AutoTagMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * video 服务内部接口: 机器打标存量回刷, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/autotag")
@RequiredArgsConstructor
public class InternalAutoTagController {

    private final AutoTagMaintenanceService autoTagMaintenanceService;
    private final ScheduleRoundLock roundLock;

    /**
     * 手动触发全量重打(冒烟/运维用), 与夜巡共用整轮锁; 同步执行, LLM 逐条打标耗时与存量成正比。
     * 默认跳过已打标内容(可续跑省 LLM 调用), force=true 强制逐条重打
     */
    @PostMapping("/rebuild")
    public Map<String, Integer> rebuild(@RequestParam(defaultValue = "false") boolean force) {
        AtomicReference<Map<String, Integer>> stats = new AtomicReference<>();
        roundLock.run("schedule:lock:video:auto-tag", () -> stats.set(autoTagMaintenanceService.rebuildAll(force)));
        // 拿不到锁(夜巡或别的实例在跑)时返回空 map
        Map<String, Integer> result = stats.get();
        return result != null ? result : Map.of();
    }
}
