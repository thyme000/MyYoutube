package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 召回编排: prep 填充共享输入 → 过滤 shouldRun → 并行扇出(单路失败降级空) → 按排序模式融合
 * 策略数无关, 加召回路只改策略实现, 这里不动
 */
@Slf4j
@Service
public class RecallPipeline {

    private final List<RecallStrategy> strategies;
    private final RrfFuser rrfFuser;
    private final LatestFuser latestFuser;
    private final FollowPort followPort;
    private final ContentVectorService contentVectorService;
    private final VectorProperties vectorProperties;
    private final ThreadPoolTaskExecutor recallExecutor;
    private final MeterRegistry metrics;

    public RecallPipeline(List<RecallStrategy> strategies,
                          RrfFuser rrfFuser,
                          LatestFuser latestFuser,
                          FollowPort followPort,
                          ContentVectorService contentVectorService,
                          VectorProperties vectorProperties,
                          MeterRegistry metrics,
                          @Qualifier("recallExecutor") ThreadPoolTaskExecutor recallExecutor) {
        this.strategies = strategies;
        this.rrfFuser = rrfFuser;
        this.latestFuser = latestFuser;
        this.followPort = followPort;
        this.contentVectorService = contentVectorService;
        this.vectorProperties = vectorProperties;
        this.metrics = metrics;
        this.recallExecutor = recallExecutor;
    }

    public List<String> execute(SearchContext ctx) {
        prep(ctx);
        List<CompletableFuture<List<String>>> futures = strategies.stream()
                .filter(s -> s.shouldRun(ctx))
                .map(s -> CompletableFuture.supplyAsync(() -> recallQuietly(s, ctx), recallExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<List<String>> recalls = futures.stream().map(CompletableFuture::join).toList();
        return ctx.isSortByLatest()
                ? latestFuser.fuse(ctx, recalls)
                : rrfFuser.fuse(ctx, recalls);
    }

    private List<String> recallQuietly(RecallStrategy strategy, SearchContext ctx) {
        try {
            return strategy.recall(ctx);
        } catch (Exception e) {
            metrics.counter("myyoutube.search.recall.failures", "strategy", strategy.getClass().getSimpleName()).increment();
            log.warn("{} 召回失败, 该路降级: {}", strategy.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    /** 共享输入只算一次挂 ctx: 综合模式解析关注列表(最新模式恒空), 有词且开关开才生成查询向量 */
    private void prep(SearchContext ctx) {
        ctx.attachFollowIds(ctx.isSortByLatest() ? List.of() : followPort.getFollowIds(ctx.getUserId()));
        if (vectorProperties.isEnabled() && !ctx.getKeyword().isEmpty()) {
            ctx.attachQueryVector(contentVectorService.embedQuery(ctx.getKeyword()));
        }
    }
}
