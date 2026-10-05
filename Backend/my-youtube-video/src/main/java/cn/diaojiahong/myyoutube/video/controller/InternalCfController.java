package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.recommend.cf.CfRecallService;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.video.api.CfRecommendPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * video 服务内部接口: 协同过滤在线召回, 供 search 服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/recommend-cf")
@RequiredArgsConstructor
public class InternalCfController {

    private final CfRecallService cfRecallService;
    private final IItemSimilarityService itemSimilarityService;

    @PostMapping("/candidates")
    public CfRecommendPort.CfCandidates candidates(@RequestBody CfRecommendPort.CfCandidatesQuery query) {
        return cfRecallService.candidates(query.getUserId(), query.getType(), query.getLimit());
    }

    /** 手动触发全量重建(冒烟/运维用), 与定时任务共用整轮锁 */
    @PostMapping("/rebuild")
    public void rebuild() {
        itemSimilarityService.rebuildAll();
    }
}
