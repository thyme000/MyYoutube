package cn.diaojiahong.myyoutube.llm.controller;

import cn.diaojiahong.myyoutube.llm.LlmRequestMetrics;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.llm.embedding.QueryEmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QueryEmbeddingController {
    private final QueryEmbeddingService queryEmbeddingService;
    private final LlmRequestMetrics metrics;

    @PostMapping("/internal/llm/query-embedding")
    public LlmPort.EmbeddingsResponse embed(@RequestBody QueryEmbeddingPort.Query request) {
        return metrics.record("query-embedding", () -> queryEmbeddingService.embed(request));
    }
}
