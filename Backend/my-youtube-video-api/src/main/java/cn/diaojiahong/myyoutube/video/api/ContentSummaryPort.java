package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * video 服务对外契约: 统一内容摘要查询(视频+动态跨类型装配)。
 * 拆部署前由 video 模块的 ContentSummaryPortImpl 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.video-service.url 指向旧单体。
 */
@FeignClient(name = "my-youtube-video-service", contextId = "contentSummaryPort", path = "/internal/content-summary",
        url = "${my-youtube.video-service.url:}")
public interface ContentSummaryPort {

    /** 按 id 批量取统一内容摘要, 返回顺序与入参一致, 不存在的 id 被剔除; 入参必须是 List, Set 过 Feign 反序列化会丢序 */
    @PostMapping("/list-ordered")
    List<ContentSummary> listByIdsOrdered(@RequestBody SummaryQuery query);

    /** viewerId 为空时只返回公开内容；身份由调用服务从登录上下文取得。 */
    record SummaryQuery(List<String> ids, Long viewerId) {}
}
