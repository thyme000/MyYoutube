package cn.diaojiahong.myyoutube.llm.embedding;

import cn.diaojiahong.myyoutube.llm.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化编排门面: 批量拆分/逐批重试/维度校验/版本盖戳全部收口在此,
 * 调用方拿到的向量必带 version 戳, 保证文档向量与查询向量同一向量空间
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingOrchestrator {

    private static final int BATCH_RETRY = 2;

    private final EmbeddingProvider embeddingProvider;
    private final LlmProperties properties;

    public EmbeddingResult embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts 不能为空");
        }
        LlmProperties.Embedding conf = properties.getEmbedding();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += Math.max(conf.getBatchSize(), 1)) {
            List<String> batch = texts.subList(from, Math.min(from + conf.getBatchSize(), texts.size()));
            vectors.addAll(embedWithRetry(batch));
        }

        int dims = vectors.get(0).length;
        if (dims != conf.getDims()) {
            throw new IllegalStateException(
                    "向量维度不符: 模型返回 " + dims + ", 配置 " + conf.getDims()
                            + ", 请检查 my-youtube.llm.embedding.dims 与 content 索引映射");
        }
        return new EmbeddingResult(vectors, dims, conf.effectiveVersion());
    }

    private List<float[]> embedWithRetry(List<String> batch) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= BATCH_RETRY; attempt++) {
            try {
                return embeddingProvider.embed(batch);
            } catch (IllegalStateException e) {
                last = e;
                // 仅剩的尝试不再记 WARN: 最终失败由调用方统一上报, 避免同一异常重复打印
                if (attempt < BATCH_RETRY) {
                    log.warn("Embedding 批量调用失败, 将重试 attempt={}/{} batchSize={} error={}",
                            attempt, BATCH_RETRY, batch.size(), e.getMessage());
                }
            }
        }
        throw last;
    }

    public record EmbeddingResult(List<float[]> vectors, int dims, String version) {
    }
}
