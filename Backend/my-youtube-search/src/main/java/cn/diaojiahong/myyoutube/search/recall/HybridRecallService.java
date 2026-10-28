package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import feign.FeignException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** 综合搜索首批的多路召回、过滤补取和相关性融合。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRecallService {
    private final List<RecallStrategy> strategies;
    private final RrfFuser rrfFuser;
    private final RecallProperties properties;
    private final MeterRegistry metrics;

    public FirstPage recall(SearchContext ctx, int size, Function<List<String>, List<String>> readable) {
        List<RecallStrategy> active = strategies.stream().filter(strategy -> strategy.shouldRun(ctx)).toList();
        List<CompletableFuture<RecallResult>> pending = active.stream()
                .map(strategy -> strategy.firstBatch(ctx).handle((batch, error) -> fallback(strategy, batch, error))).toList();
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("搜索等待已中断", interrupted);
        } catch (ExecutionException error) {
            throw new CompletionException(unwrap(error));
        }
        List<RecallResult> results = pending.stream().map(CompletableFuture::join).toList();
        // 单路可以降级，全部失败不能伪装成“没有搜索结果”。
        if (!results.isEmpty() && results.stream().allMatch(result -> result.failure() != null))
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "搜索暂时不可用，请稍后重试", results.get(0).failure());
        List<RecallBatch> batches = new ArrayList<>(results.stream().map(RecallResult::batch).toList());
        FirstPage page = filterAndRefill(ctx, active, batches, readable, size);
        boolean retryKeyword = false;
        for (int route = 0; route < active.size(); route++) {
            if (active.get(route).supportsPaging() && results.get(route).failure() != null) retryKeyword = true;
        }
        return new FirstPage(page.ids(), page.hasMore() || retryKeyword);
    }

    private RecallResult fallback(RecallStrategy strategy, RecallBatch batch, Throwable error) {
        if (error == null) return new RecallResult(batch, null);
        Throwable cause = unwrap(error);
        if (!unavailable(cause)) throw new CompletionException(cause);
        String name = AopUtils.getTargetClass(strategy).getSimpleName();
        metrics.counter(MetricNames.SEARCH_RECALL_FAILURES, "strategy", name).increment();
        log.warn("{} 召回降级 errorType={}", name, cause.getClass().getSimpleName());
        return new RecallResult(RecallBatch.exhausted(List.of()), cause);
    }

    private FirstPage filterAndRefill(SearchContext ctx, List<RecallStrategy> active, List<RecallBatch> batches,
                                     Function<List<String>, List<String>> readable, int size) {
        List<String> initial = batches.stream().flatMap(batch -> batch.ids().stream()).distinct()
                .limit(properties.getRefillMaxCandidates()).toList();
        if (initial.isEmpty()) return new FirstPage(List.of(), false);

        Set<String> checked = new LinkedHashSet<>(initial);
        Set<String> visible = new HashSet<>(readable.apply(initial));
        int target = Math.min(initial.size(), size);
        List<List<String>> recalls = refill(ctx, active, batches, readable, checked, visible, target);

        // 保留各路原始排名参与融合，只展示已经回源确认可见的候选。
        List<List<String>> checkedRecalls = recalls.stream()
            .map(ids -> ids.stream().filter(checked::contains).distinct().toList()).toList();
        List<String> ranked = rrfFuser.fuse(checkedRecalls);
        List<String> page = ranked.stream().filter(visible::contains).limit(target).toList();
        Set<String> displayed = Set.copyOf(page);
        boolean more = false;
        for (int route = 0; route < active.size(); route++) {
            if (!active.get(route).supportsPaging()) continue;
            // 回源检查有数量上限，原始召回中仍可能留有未检查候选。
            boolean unchecked = recalls.get(route).stream().anyMatch(id -> !checked.contains(id));
            boolean undisplayed = recalls.get(route).stream().anyMatch(id -> visible.contains(id) && !displayed.contains(id));
            more |= batches.get(route).hasMore() || unchecked || undisplayed;
        }
        return new FirstPage(page, more);
    }

    private List<List<String>> refill(SearchContext ctx, List<RecallStrategy> active, List<RecallBatch> batches,
                                      Function<List<String>, List<String>> readable, Set<String> checked, Set<String> visible,
                                      int target) {
        List<List<String>> recalls = new ArrayList<>();
        for (RecallBatch batch : batches) recalls.add(new ArrayList<>(batch.ids()));

        int rounds = 0;
        for (int route = 0; route < active.size() && visible.size() < target; route++) {
            RecallBatch previous = batches.get(route);
            while (visible.size() < target && previous.hasMore() && rounds < properties.getRefillMaxRounds()
                    && checked.size() < properties.getRefillMaxCandidates()) {
                int size = Math.min(properties.getRefillBatchSize(), properties.getRefillMaxCandidates() - checked.size());
                try {
                    RecallBatch next = active.get(route).nextBatch(ctx, previous, size);
                    rounds++;
                    metrics.counter(MetricNames.SEARCH_REFILL_ROUNDS).increment();
                    previous = next;

                    // 跨路重复仍参与排名计算，但同一个 ID 只回源检查一次。
                    recalls.get(route).addAll(next.ids());
                    List<String> unchecked = next.ids().stream().filter(checked::add).toList();
                    if (!unchecked.isEmpty()) visible.addAll(readable.apply(unchecked));
                    batches.set(route, next);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("搜索补取已中断", interrupted);
                } catch (Exception error) {
                    Throwable cause = unwrap(error);
                    if (!unavailable(cause)) throw new CompletionException(cause);

                    metrics.counter(MetricNames.SEARCH_REFILL_FAILURES).increment();
                    log.warn("Search refill stopped errorType={}", cause.getClass().getSimpleName());
                    break;
                }
            }
        }
        return recalls;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    private static boolean unavailable(Throwable error) {
        return error instanceof TimeoutException || error instanceof RejectedExecutionException
                || error instanceof IOException || error instanceof FeignException
                || error instanceof DependencyException || error instanceof ModelUnavailable;
    }

    private record RecallResult(RecallBatch batch, Throwable failure) {}

    /** 后续只续查关键词，未展示的纯语义候选不构成下一页。 */
    public record FirstPage(List<String> ids, boolean hasMore) {}
}
