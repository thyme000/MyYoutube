package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import cn.diaojiahong.myyoutube.common.concurrent.AsyncTimeout;

/**
 * 向量语义召回路: kNN 只召回与查询向量同版本的已发布内容
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class VectorRecallStrategy implements RecallStrategy {

    private final QueryVectorRecall queryVectorRecall;
    private final VectorProperties vectorProperties;

    @Override
    public boolean shouldRun(SearchContext ctx) {
        return !ctx.isTagSearch() && !ctx.isSortByLatest() && vectorProperties.isEnabled() && !ctx.getKeyword().isEmpty();
    }

    @Override
    @AsyncTimeout("vector-recall")
    public CompletableFuture<RecallBatch> firstBatch(SearchContext ctx) {
        try {
            return CompletableFuture.completedFuture(RecallBatch.exhausted(queryVectorRecall.recall(ctx)));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
