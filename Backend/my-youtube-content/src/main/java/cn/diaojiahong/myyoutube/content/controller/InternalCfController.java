package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.recommend.cf.CfRecallService;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.content.api.CfRecommendPort;
import cn.diaojiahong.myyoutube.video.schedule.ContentScheduleLocks;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容服务内部接口: 协同过滤在线召回, 供 search 服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/recommend-cf")
@RequiredArgsConstructor
public class InternalCfController {

    private final CfRecallService cfRecallService;
    private final IItemSimilarityService itemSimilarityService;
    private final ScheduleRoundLock roundLock;

    @PostMapping("/candidates")
    public CfRecommendPort.CfCandidates candidates(@RequestBody CfRecommendPort.CfCandidatesQuery query) {
        return cfRecallService.candidates(query.getUserId(), query.getType(), query.getLimit());
    }

    /** 手动触发全量重建(冒烟/运维用), 与定时任务共用整轮锁 */
    @PostMapping("/rebuild")
    public void rebuild() {
        roundLock.run(ContentScheduleLocks.CF_REBUILD, itemSimilarityService::rebuildAll);
    }
}
