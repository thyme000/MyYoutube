package cn.diaojiahong.myyoutube.search.recall;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 召回路负责查询，异步等待由 @AsyncTimeout 切面管理。 */
public interface RecallStrategy {
    boolean shouldRun(SearchContext ctx);
    default boolean supportsPaging() { return false; }
    CompletableFuture<RecallBatch> firstBatch(SearchContext ctx);
    default RecallBatch nextBatch(SearchContext ctx, RecallBatch previous, int size) throws Exception {
        return RecallBatch.exhausted(List.of());
    }
}
