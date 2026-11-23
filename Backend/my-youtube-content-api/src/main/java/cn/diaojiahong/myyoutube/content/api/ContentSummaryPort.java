package cn.diaojiahong.myyoutube.content.api;

import cn.diaojiahong.myyoutube.content.api.dto.ContentSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 内容服务对外契约: 统一内容摘要查询(视频与动态跨类型装配)。
 * 直连调试时设置 my-youtube.content-service.url，为空则走 Nacos 负载均衡。
 */
@FeignClient(name = "my-youtube-content-service", contextId = "contentSummaryPort", path = "/internal/content-summary",
        url = "${my-youtube.content-service.url:}")
public interface ContentSummaryPort {

    /** 按 id 批量取统一内容摘要, 返回顺序与入参一致, 不存在的 id 被剔除; 入参必须是 List, Set 过 Feign 反序列化会丢序 */
    @PostMapping("/list-ordered")
    List<ContentSummary> listByIdsOrdered(@RequestBody SummaryQuery query);

    /** viewerId 为空时只返回公开内容；身份由调用服务从登录上下文取得。 */
    record SummaryQuery(List<String> ids, Long viewerId) {
    }
}
