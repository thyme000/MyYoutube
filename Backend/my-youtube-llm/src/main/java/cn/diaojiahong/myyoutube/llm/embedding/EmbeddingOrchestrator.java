package cn.diaojiahong.myyoutube.llm.embedding;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.LlmProperties;
import cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingOrchestrator {
    private static final int BATCH_RETRY = 2;
    private final EmbeddingProvider embeddingProvider;
    private final LlmProperties properties;

    public EmbeddingResult embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) throw new IllegalArgumentException("texts 不能为空");
        var conf = properties.getEmbedding();
        int dims = conf.getDims();
        String version = conf.effectiveVersion();
        int batchSize = Math.max(conf.getBatchSize(), 1);
        List<EmbeddingItem> items = new ArrayList<>(Collections.nCopies(texts.size(), null));
        String unavailable = null;
        boolean hasResults = false;
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            try {
                List<float[]> vectors;
                try {
                    vectors = embedWithRetry(batch);
                } catch (ModelUnavailable error) {
                    throw error;
                } catch (IllegalStateException error) {
                    if (batch.size() == 1) {
                        items.set(from, new EmbeddingItem(from, null, reason(error)));
                        hasResults = true;
                        continue;
                    }
                    log.warn("Embedding 子批次失败，仅隔离本批 {} 项", batch.size());
                    for (int i = 0; i < batch.size(); i++) {
                        items.set(from + i, isolate(from + i, batch.get(i), dims));
                        hasResults = true;
                    }
                    continue;
                }
                // 先保留本批所有有效向量，避免隔离中断丢失后续已返回结果。
                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = vectors == null || vectors.size() != batch.size() ? null : vectors.get(i);
                    if (valid(vector, dims)) {
                        items.set(from + i, success(from + i, vector));
                        hasResults = true;
                    }
                }
                for (int i = 0; i < batch.size(); i++) {
                    if (items.get(from + i) == null) {
                        items.set(from + i, batch.size() == 1 ? new EmbeddingItem(from, null, "模型向量维度或数值非法")
                                : isolate(from + i, batch.get(i), dims));
                        hasResults = true;
                    }
                }
            } catch (ModelUnavailable error) {
                if (!hasResults) throw error;
                unavailable = reason(error);
                for (int index = from; index < texts.size(); index++) {
                    if (items.get(index) == null) items.set(index, new EmbeddingItem(index, null, unavailable));
                }
                break;
            }
        }
        return new EmbeddingResult(items, dims, version, unavailable);
    }

    private EmbeddingItem isolate(int index, String text, int dims) {
        try {
            // 子批次重试已耗尽或已返回坏向量，逐条隔离仅再调用一次。
            List<float[]> vectors = embeddingProvider.embed(List.of(text));
            float[] vector = vectors != null && vectors.size() == 1 ? vectors.get(0) : null;
            return valid(vector, dims) ? success(index, vector)
                    : new EmbeddingItem(index, null, "模型向量维度或数值非法");
        } catch (ModelUnavailable error) {
            throw error;
        } catch (IllegalStateException error) {
            return new EmbeddingItem(index, null, reason(error));
        }
    }

    private boolean valid(float[] vector, int dims) {
        if (vector == null || vector.length != dims) return false;
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private EmbeddingItem success(int index, float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        return new EmbeddingItem(index, values, null);
    }

    private String reason(RuntimeException error) {
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        return reason.substring(0, Math.min(500, reason.length()));
    }

    private List<float[]> embedWithRetry(List<String> batch) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= BATCH_RETRY; attempt++) {
            try {
                return embeddingProvider.embed(batch);
            } catch (ModelUnavailable error) {
                throw error;
            } catch (IllegalStateException error) {
                last = error;
                if (attempt < BATCH_RETRY) log.warn("Embedding 批量调用失败，将重试 attempt={}/{} batchSize={} error={}",
                        attempt, BATCH_RETRY, batch.size(), error.getMessage());
            }
        }
        throw last;
    }

    public record EmbeddingResult(List<EmbeddingItem> items, int dims, String version, String unavailableReason) {}
}
