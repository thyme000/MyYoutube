package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Set;

/**
 * video 服务对外契约: 统一内容摘要查询(视频+动态跨类型装配)。
 * 拆部署前由 video 模块的 ContentSummaryPortImpl 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.video-service.url 指向旧单体。
 */
@FeignClient(name = "my-youtube-video-service", contextId = "contentSummaryPort", path = "/internal/content-summary",
        url = "${my-youtube.video-service.url:}")
public interface ContentSummaryPort {

    /** 按 id 批量取统一内容摘要, 返回顺序与入参一致, 不存在的 id 被剔除 */
    @PostMapping("/list-ordered")
    List<ContentSummary> listByIdsOrdered(@RequestBody Set<String> ids);
}
