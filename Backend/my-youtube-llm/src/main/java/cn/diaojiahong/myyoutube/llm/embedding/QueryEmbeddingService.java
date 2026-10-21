package cn.diaojiahong.myyoutube.llm.embedding;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.LlmProperties;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

@Service
public class QueryEmbeddingService {
    private final EmbeddingProvider provider;
    private final LlmProperties properties;
    // 保护本实例的查询调用；共享模型的全局限流应由模型入口统一执行。
    private final Semaphore slots;

    public QueryEmbeddingService(EmbeddingProvider provider, LlmProperties properties) {
        this.provider = provider;
        this.properties = properties;
        this.slots = new Semaphore(properties.getEmbedding().getQueryMaxConcurrent());
    }

    public QueryEmbeddingPort.Info info() {
        var conf = properties.getEmbedding();
        return new QueryEmbeddingPort.Info(conf.effectiveVersion(), conf.getDims());
    }

    public LlmPort.EmbeddingsResponse embed(QueryEmbeddingPort.Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            throw new IllegalArgumentException("查询文本不能为空");
        }
        if (!slots.tryAcquire()) throw new ModelUnavailable("LLM", "QUERY_EMBEDDING_BUSY", 1, null);
        try {
            var conf = properties.getEmbedding();
            long remaining = Math.min(query.deadlineEpochMillis() - System.currentTimeMillis(), conf.getQueryTimeoutMillis());
            if (remaining <= 0) throw new ModelUnavailable("LLM", "QUERY_EMBEDDING_EXPIRED", 1, null);
            var vectors = provider.embedQuery(query.text(), Duration.ofMillis(remaining));
            if (vectors == null || vectors.size() != 1 || vectors.get(0) == null
                    || vectors.get(0).length != conf.getDims()) throw new IllegalStateException("查询向量维度非法");
            List<Float> values = new ArrayList<>(conf.getDims());
            for (float value : vectors.get(0)) {
                if (!Float.isFinite(value)) throw new IllegalStateException("查询向量数值非法");
                values.add(value);
            }
            return new LlmPort.EmbeddingsResponse(List.of(values), conf.getDims(), conf.effectiveVersion());
        } finally {
            slots.release();
        }
    }
}
