package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.port.ContentSummaryPortImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * video 服务内部接口: 统一内容摘要查询(供 search 等服务经 Feign 调用)
 */
@RestController
@RequestMapping("/internal/content-summary")
@RequiredArgsConstructor
public class InternalContentSummaryController {

    private final ContentSummaryPortImpl contentSummaryPort;

    @PostMapping("/list-ordered")
    public List<ContentSummary> listByIdsOrdered(@RequestBody List<String> ids) {
        return contentSummaryPort.listByIdsOrdered(ids);
    }
}
