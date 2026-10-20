package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.search.recall.RecallSearchClient;
import feign.Request;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static cn.diaojiahong.myyoutube.search.recall.RecallSearchClient.remainingMillis;

@Service
@RequiredArgsConstructor
public class QueryVectorRecall {
    private final QueryEmbeddingPort embeddings;
    private final RecallSearchClient searchClient;
    private final VectorProperties properties;

    public List<String> recall(String keyword, String category, long deadlineNanos) throws Exception {
        long remaining = remainingMillis(deadlineNanos);
        var response = embeddings.embed(new QueryEmbeddingPort.Query(keyword, System.currentTimeMillis() + remaining),
                new Request.Options(remaining, TimeUnit.MILLISECONDS, remaining, TimeUnit.MILLISECONDS, true));
        remainingMillis(deadlineNanos);
        if (response == null || response.getUnavailableReason() != null || response.getItems() == null
                || response.getItems().size() != 1 || response.getVersion() == null || response.getVersion().isBlank()) return List.of();
        var item = response.getItems().get(0);
        if (item.index() != 0 || item.error() != null || item.vector() == null || item.vector().isEmpty()
                || response.getDims() == null || response.getDims() != item.vector().size()
                || item.vector().stream().anyMatch(value -> value == null || !Float.isFinite(value))) return List.of();
        var vector = new ContentVectorService.QueryVector(item.vector(), response.getVersion());
        return searchClient.search(ContentVectorService.knnRequest(vector, category, properties.getRecallSize()), deadlineNanos);
    }
}
