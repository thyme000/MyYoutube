package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static cn.diaojiahong.myyoutube.search.hot.HotSearchMetrics.Operation.*;
import static cn.diaojiahong.myyoutube.search.hot.HotSearchMetrics.RecordResult.*;
import static cn.diaojiahong.myyoutube.search.hot.HotSearchMetrics.EventResult.*;

@Service
@Slf4j
public class HotSearchService {
    // 原文另存，不截断；超限只跳过热搜，限制缓存和数据库记录体积。
    private static final int MAX_RAW_LENGTH = 1024;
    private final HotSearchStore store;
    private final HotSearchHistoryStore history;
    private final HotSearchModel model;
    private final HotSearchFilter filter;
    private final HotSearchProperties properties;
    private final IdempotentGuard guard;
    private final ThreadPoolTaskExecutor cacheExecutor;
    private final Clock clock;
    private final HotSearchMetrics metrics;

    public HotSearchService(HotSearchStore store, HotSearchHistoryStore history, HotSearchModel model, HotSearchFilter filter,
                            HotSearchProperties properties, IdempotentGuard guard,
                            @Qualifier("hotSearchCacheExecutor") ThreadPoolTaskExecutor cacheExecutor, Clock clock,
                            HotSearchMetrics metrics) {
        this.store = store;
        this.history = history;
        this.model = model;
        this.filter = filter;
        this.properties = properties;
        this.guard = guard;
        this.cacheExecutor = cacheExecutor;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void record(long userId, String keyword) {
        if (!properties.isEnabled()) return;
        if (keyword == null || keyword.codePointCount(0, keyword.length()) > MAX_RAW_LENGTH) {
            metrics.record(FILTERED);
            return;
        }
        String text = filter.eligible(keyword.trim());
        if (text == null) { metrics.record(FILTERED); return; }
        var event = new HotSearchStore.Event(UUID.randomUUID().toString(), text, keyword, userId, clock.millis());
        try {
            cacheExecutor.execute(() -> {
                try {
                    if (store.record(event)) metrics.record(ACCEPTED);
                    else { metrics.record(CAPACITY); log.warn("Hot search record dropped: pending capacity reached"); }
                } catch (RuntimeException error) {
                    metrics.record(FAILURE);
                    log.warn("Hot search record failed errorType={}", error.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException error) {
            metrics.record(REJECTED);
            log.warn("Hot search record dropped: cache executor unavailable");
        }
    }

    @ObservedBusiness(BusinessOperation.HOT_SEARCH)
    public List<String> hot() {
        if (!properties.isEnabled()) return List.of();
        return read(HotSearchMetrics.ReadKind.HOT, store::hot, List.<String>of(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))
                .stream().filter(name -> filter.eligible(name) != null).limit(properties.getTopLimit()).toList();
    }

    public HotSearchGroup cachedGroup(String text, long deadlineNanos) {
        if (!properties.isEnabled() || filter.eligible(text) == null) return null;
        return read(HotSearchMetrics.ReadKind.NAMED, () -> store.named(text), null, deadlineNanos);
    }

    private <T> T read(HotSearchMetrics.ReadKind kind, Supplier<T> operation, T fallback, long deadlineNanos) {
        Future<T> task = null;
        try {
            long remaining = Math.min(TimeUnit.MILLISECONDS.toNanos(50), deadlineNanos - System.nanoTime());
            if (remaining <= 0) { metrics.read(kind, HotSearchMetrics.ReadResult.BUDGET); return fallback; }
            task = cacheExecutor.submit(operation::get);
            T value = task.get(remaining, TimeUnit.NANOSECONDS);
            metrics.read(kind, value == null || value instanceof Collection<?> values && values.isEmpty()
                    ? HotSearchMetrics.ReadResult.MISS : HotSearchMetrics.ReadResult.HIT);
            return value;
        } catch (InterruptedException error) {
            metrics.read(kind, HotSearchMetrics.ReadResult.INTERRUPTED);
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception error) {
            metrics.read(kind, error instanceof java.util.concurrent.TimeoutException ? HotSearchMetrics.ReadResult.TIMEOUT
                    : error instanceof java.util.concurrent.RejectedExecutionException ? HotSearchMetrics.ReadResult.REJECTED
                    : HotSearchMetrics.ReadResult.FAILURE);
            log.debug("Hot search cache unavailable: {}", error.getClass().getSimpleName());
            return fallback;
        } finally {
            if (task != null && !task.isDone()) task.cancel(true);
        }
    }

    @Scheduled(fixedDelayString = "${my-youtube.search.hot.refresh-millis:300000}",
            initialDelayString = "${my-youtube.search.hot.refresh-millis:300000}")
    public void refresh() {
        if (!properties.isEnabled()) return;
        long started = System.nanoTime();
        try (var lease = guard.acquire("{hot-search}:refresh", IdempotentGuard.DEFAULT_LEASE_SECONDS)) {
            if (lease.acquired()) metrics.run(REFRESH, () -> refreshRound(lease, started + TimeUnit.SECONDS.toNanos(properties.getRoundBudgetSeconds())));
        } catch (RuntimeException error) { log.warn("Hot search refresh skipped or failed", error); }
    }

    private void refreshRound(IdempotentGuard.Lease lease, long deadlineNanos) {
        refreshRound(lease, deadlineNanos, null);
    }

    void finishDay(LocalDate date, IdempotentGuard.Lease lease, long deadlineNanos) {
        metrics.run(FINAL_PASS, () -> refreshRound(lease, deadlineNanos,
                date.plusDays(1).atStartOfDay(HotSearchStore.STAT_ZONE).toInstant().toEpochMilli()));
    }

    private void refreshRound(IdempotentGuard.Lease lease, long deadlineNanos, Long beforeExclusive) {
        LocalDate closedThrough = store.closedThrough();
        Map<HotSearchStore.Event, HotSearchStore.Assignment> ready = new LinkedHashMap<>();
        var pendingByKeyword = loadPendingEvents(lease, ready, beforeExclusive);
        try {
            refreshGroups(lease, deadlineNanos, pendingByKeyword, ready);
        } finally {
            persistHistory(ready, lease, closedThrough);
        }
    }

    private void refreshGroups(IdempotentGuard.Lease lease, long deadlineNanos,
                               Map<String, List<HotSearchStore.Event>> pendingByKeyword,
                               Map<HotSearchStore.Event, HotSearchStore.Assignment> ready) {
        var info = metrics.call(MODEL_INFO, model::info);
        var groups = loadActiveGroups(lease, info);
        List<String> keywords = pendingByKeyword.keySet().stream().limit(properties.getMaxDistinctPerRound()).toList();
        var cachedGroups = resolveCachedGroups(keywords, info, groups);
        List<String> unknown = keywords.stream().filter(keyword -> !cachedGroups.containsKey(keyword)).toList();
        Map<String, List<Float>> vectors = Map.of();
        if (!unknown.isEmpty()) {
            try {
                vectors = vectors(unknown, info);
            } catch (RuntimeException error) {
                log.warn("Hot search embeddings deferred batchSize={} version={} dims={} cached={}",
                        unknown.size(), info.version(), info.dims(), cachedGroups.size(), error);
                if (cachedGroups.isEmpty()) return;
            }
        }

        List<String> unmatched = new ArrayList<>();
        int processed = 0;
        for (String keyword : keywords) {
            if (System.nanoTime() >= deadlineNanos) break;
            lease.checkActive();
            try {
                HotSearchGroup group = cachedGroups.get(keyword);
                if (group == null) {
                    var vector = vectors.get(keyword);
                    if (vector == null) continue;
                    group = findSimilarGroup(groups.values(), vector);
                    if (group == null) {
                        unmatched.add(keyword);
                        continue;
                    }
                }
                processed += contribute(keyword, group, pendingByKeyword.get(keyword), groups, ready, lease);
            } catch (RuntimeException error) {
                log.warn("Hot search item deferred keywordId={} version={}", HotSearchStore.digest(keyword), info.version(), error);
            }
        }
        if (!unmatched.isEmpty() && System.nanoTime() < deadlineNanos) {
            try {
                lease.assertOwned();
                var assignments = groupUnmatched(unmatched, vectors, info, groups.values(), lease, deadlineNanos);
                for (String keyword : unmatched) {
                    if (System.nanoTime() >= deadlineNanos) break;
                    var group = assignments.get(keyword);
                    if (group != null) processed += contribute(keyword, group, pendingByKeyword.get(keyword), groups, ready, lease);
                }
            } catch (RuntimeException error) {
                log.warn("Hot search grouping deferred batchSize={} version={}", unmatched.size(), info.version(), error);
            }
        }
        int displayed = publishRankings(groups.values(), lease);
        log.info("Hot search refreshed processed={} groups={} displayed={}", processed, groups.size(), displayed);
    }

    private Map<String, HotSearchGroup> loadActiveGroups(IdempotentGuard.Lease lease, QueryEmbeddingPort.Info info) {
        Map<String, HotSearchGroup> groups = new LinkedHashMap<>();
        List<HotSearchGroup> retired = new ArrayList<>();
        for (var group : store.groups(clock.millis())) {
            if (!eligible(group, info)) {
                retired.add(group);
                continue;
            }
            groups.put(group.id(), group);
        }
        if (!retired.isEmpty()) {
            lease.assertOwned();
            store.retire(retired, clock.millis());
        }
        return groups;
    }

    private Map<String, List<HotSearchStore.Event>> loadPendingEvents(
            IdempotentGuard.Lease lease, Map<HotSearchStore.Event, HotSearchStore.Assignment> ready, Long beforeExclusive) {
        Map<String, List<HotSearchStore.Event>> pendingByKeyword = new LinkedHashMap<>();
        var pending = beforeExclusive == null ? store.pending(clock.millis()) : store.pending(clock.millis(), beforeExclusive);
        var assignments = store.assignments(pending);
        List<HotSearchStore.Event> excluded = new ArrayList<>();
        for (var event : pending) {
            var assignment = assignments.get(event);
            if (assignment != null) {
                ready.put(event, assignment);
                continue;
            }
            String keyword = filter.eligible(event.keyword());
            if (keyword == null) {
                excluded.add(event);
                continue;
            }
            var events = pendingByKeyword.computeIfAbsent(keyword, k -> new ArrayList<>());
            events.add(event);
        }
        if (!excluded.isEmpty()) {
            lease.assertOwned();
            store.remove(excluded);
            metrics.events(EXCLUDED, excluded.size());
        }
        return pendingByKeyword;
    }

    private Map<String, HotSearchGroup> resolveCachedGroups(List<String> keywords, QueryEmbeddingPort.Info info,
                                                           Map<String, HotSearchGroup> groups) {
        Map<String, HotSearchGroup> cachedGroups = new HashMap<>();
        var aliases = store.aliases(keywords, info.version());
        Map<String, HotSearchGroup> named = new HashMap<>();
        groups.values().forEach(group -> named.putIfAbsent(group.name(), group));
        for (String keyword : keywords) {
            var group = groups.get(aliases.get(keyword));
            if (!eligible(group, info)) group = named.get(keyword);
            if (eligible(group, info)) cachedGroups.put(keyword, group);
        }
        return cachedGroups;
    }

    private HotSearchGroup findSimilarGroup(Collection<HotSearchGroup> groups, List<Float> vector) {
        HotSearchGroup best = null;
        double bestSimilarity = properties.getSimilarityThreshold();
        for (var candidate : groups) {
            double similarity = candidate.similarity(vector);
            if (similarity >= properties.getSimilarityThreshold() && (best == null || similarity > bestSimilarity)) {
                best = candidate;
                bestSimilarity = similarity;
            }
        }
        return best;
    }

    private Map<String, HotSearchGroup> groupUnmatched(List<String> keywords, Map<String, List<Float>> vectors,
                                                      QueryEmbeddingPort.Info info, Collection<HotSearchGroup> groups,
                                                      IdempotentGuard.Lease lease, long deadlineNanos) {
        Map<String, List<String>> keywordsByName = new LinkedHashMap<>();
        var grouped = metrics.call(GROUPING, () -> model.group(keywords));
        metrics.modelItems(GROUPING, keywords.size(), grouped.stream().mapToInt(group -> group.keywords().size()).sum());
        for (var candidate : grouped) {
            String name = filter.eligible(candidate.name());
            if (name != null) keywordsByName.computeIfAbsent(name, key -> new ArrayList<>()).addAll(candidate.keywords());
        }
        if (System.nanoTime() >= deadlineNanos) return Map.of();
        lease.assertOwned();
        Map<String, HotSearchGroup> groupsByName = new HashMap<>();
        for (var group : groups) groupsByName.put(group.name(), group);
        List<String> newNames = keywordsByName.keySet().stream().filter(name -> !groupsByName.containsKey(name))
                .limit(properties.getMaxNewGroupsPerRound()).toList();
        List<String> missingVectors = newNames.stream().filter(name -> !vectors.containsKey(name)).toList();
        Map<String, List<Float>> nameVectors = Map.of();
        if (!missingVectors.isEmpty()) {
            try {
                nameVectors = vectors(missingVectors, info);
            } catch (RuntimeException error) {
                log.warn("Hot search group embeddings deferred batchSize={} version={}", missingVectors.size(), info.version(), error);
            }
        }
        for (String name : newNames) {
            var vector = vectors.containsKey(name) ? vectors.get(name) : nameVectors.get(name);
            if (vector != null) groupsByName.put(name,
                    new HotSearchGroup(HotSearchStore.digest(info.version() + "\n" + name), name, vector, info.version()));
        }
        Map<String, HotSearchGroup> assignments = new HashMap<>();
        keywordsByName.forEach((name, members) -> {
            var group = groupsByName.get(name);
            if (group != null) members.forEach(keyword -> assignments.put(keyword, group));
        });
        return assignments;
    }

    private int contribute(String keyword, HotSearchGroup group, List<HotSearchStore.Event> events,
                           Map<String, HotSearchGroup> groups,
                           Map<HotSearchStore.Event, HotSearchStore.Assignment> ready, IdempotentGuard.Lease lease) {
        int processed = 0;
        long started = System.nanoTime();
        boolean success = false;
        try {
            lease.assertOwned();
            var assignments = store.assign(group, events, clock.millis());
            ready.putAll(assignments);
            var matching = events.stream().filter(event -> group.id().equals(assignments.get(event).groupId())).toList();
            boolean contributed = store.contribute(group, matching, clock.millis());
            processed = assignments.size();
            if (contributed) {
                groups.put(group.id(), group);
                lease.assertOwned();
                store.remember(keyword, group);
            }
            success = true;
        } catch (RuntimeException error) {
            log.warn("Hot search item deferred keywordId={} version={} errorType={}",
                    HotSearchStore.digest(keyword), group.version(), error.getClass().getSimpleName());
        } finally { metrics.completed(CONTRIBUTION, started, success); }
        return processed;
    }

    private void persistHistory(Map<HotSearchStore.Event, HotSearchStore.Assignment> ready, IdempotentGuard.Lease lease,
                                LocalDate closedThrough) {
        if (ready.isEmpty()) return;
        try {
            lease.assertOwned();
            var events = ready.entrySet().stream().map(entry -> {
                var event = entry.getKey();
                var assignment = entry.getValue();
                LocalDate date = LocalDate.parse(assignment.statDate());
                // 已收尾日期不再接纳新落库；SQL 中已存在的 event_id 仍保留原日期。
                if (closedThrough != null && !date.isAfter(closedThrough)) date = closedThrough.plusDays(1);
                return new HotSearchHistoryStore.GroupedEvent(event.token(), date,
                        event.happenedAt(), event.userId(), event.rawKeyword(), event.keyword(),
                        assignment.groupId(), assignment.groupName(), assignment.version());
            }).toList();
            metrics.run(HISTORY_WRITE, () -> history.append(events));
            metrics.events(COMMITTED, events.size());
            lease.assertOwned();
            metrics.run(HISTORY_ACK, () -> store.remove(ready.keySet()));
            metrics.events(ACKNOWLEDGED, events.size());
        } catch (RuntimeException error) {
            metrics.events(DEFERRED, ready.size());
            log.warn("Hot search history deferred batchSize={} errorType={}", ready.size(), error.getClass().getSimpleName());
        }
    }

    private int publishRankings(Collection<HotSearchGroup> groups, IdempotentGuard.Lease lease) {
        long now = clock.millis();
        List<HotSearchStore.Ranking> rankings = new ArrayList<>();
        List<HotSearchGroup> retired = new ArrayList<>();
        lease.assertOwned();
        for (var ranking : store.rankings(groups, now)) {
            if (ranking.score() == 0) retired.add(ranking.group());
            else if (ranking.users() >= properties.getMinUsers()) rankings.add(ranking);
        }
        if (!retired.isEmpty()) {
            lease.assertOwned();
            store.retire(retired, now);
        }
        List<String> names = rankings.stream().sorted(Comparator.comparingLong(HotSearchStore.Ranking::score).reversed()
                        .thenComparing(value -> value.group().id()))
                .map(value -> value.group().name()).distinct().limit(properties.getTopLimit()).toList();
        lease.assertOwned();
        metrics.run(PUBLISH, () -> store.publish(names));
        metrics.published(groups.size() - retired.size(), names.size(), clock.millis());
        return names.size();
    }

    private Map<String, List<Float>> vectors(List<String> texts, QueryEmbeddingPort.Info info) {
        var result = metrics.call(EMBEDDING, () -> model.vectors(texts, info));
        metrics.modelItems(EMBEDDING, texts.size(), result.size());
        return result;
    }

    private boolean eligible(HotSearchGroup group, QueryEmbeddingPort.Info info) {
        return group != null && group.valid(info.version(), info.dims()) && filter.eligible(group.name()) != null;
    }
}
