package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@Slf4j
public class HotSearchService {
    private final HotSearchStore store;
    private final HotSearchModel model;
    private final HotSearchFilter filter;
    private final HotSearchProperties properties;
    private final IdempotentGuard guard;
    private final ThreadPoolTaskExecutor cacheExecutor;
    private final Clock clock;

    public HotSearchService(HotSearchStore store, HotSearchModel model, HotSearchFilter filter,
                            HotSearchProperties properties, IdempotentGuard guard,
                            @Qualifier("hotSearchCacheExecutor") ThreadPoolTaskExecutor cacheExecutor, Clock clock) {
        this.store = store;
        this.model = model;
        this.filter = filter;
        this.properties = properties;
        this.guard = guard;
        this.cacheExecutor = cacheExecutor;
        this.clock = clock;
    }

    public void record(long userId, String keyword) {
        if (!properties.isEnabled()) return;
        String text = filter.eligible(keyword);
        if (text == null) return;
        var event = new HotSearchStore.Event(UUID.randomUUID().toString(), text, userId, clock.millis());
        try {
            cacheExecutor.execute(() -> {
                try {
                    if (!store.record(event)) log.warn("Hot search record dropped: pending capacity reached");
                } catch (RuntimeException error) { log.warn("Hot search record failed", error); }
            });
        } catch (RuntimeException error) { log.warn("Hot search record dropped: cache executor unavailable"); }
    }

    public List<String> hot() {
        if (!properties.isEnabled()) return List.of();
        return read(store::hot, List.<String>of(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))
                .stream().filter(name -> filter.eligible(name) != null).limit(7).toList();
    }

    public HotSearchGroup cachedGroup(String text, long deadlineNanos) {
        if (!properties.isEnabled() || filter.eligible(text) == null) return null;
        return read(() -> store.named(text), null, deadlineNanos);
    }

    private <T> T read(Supplier<T> operation, T fallback, long deadlineNanos) {
        Future<T> task = null;
        try {
            long remaining = Math.min(TimeUnit.MILLISECONDS.toNanos(50), deadlineNanos - System.nanoTime());
            if (remaining <= 0) return fallback;
            task = cacheExecutor.submit(operation::get);
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception error) {
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
            if (lease.acquired()) refreshRound(lease, started + TimeUnit.SECONDS.toNanos(properties.getRoundBudgetSeconds()));
        } catch (RuntimeException error) { log.warn("Hot search refresh skipped or failed", error); }
    }

    private void refreshRound(IdempotentGuard.Lease lease, long deadlineNanos) {
        var info = model.info();
        var groups = loadActiveGroups(lease, info);
        var pendingByKeyword = loadPendingEvents(lease);
        List<String> keywords = pendingByKeyword.keySet().stream().limit(properties.getMaxDistinctPerRound()).toList();
        var cachedGroups = resolveCachedGroups(keywords, info);
        List<String> unknown = keywords.stream().filter(keyword -> !cachedGroups.containsKey(keyword)).toList();
        Map<String, List<Float>> vectors = Map.of();
        if (!unknown.isEmpty()) {
            try {
                vectors = model.vectors(unknown, info);
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
            lease.assertOwned();
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
                processed += contribute(keyword, group, pendingByKeyword.get(keyword), groups, lease);
            } catch (RuntimeException error) {
                log.warn("Hot search item deferred keywordId={} version={}", HotSearchStore.digest(keyword), info.version(), error);
            }
        }
        if (!unmatched.isEmpty() && groups.size() < properties.getGroupLimit() && System.nanoTime() < deadlineNanos) {
            try {
                lease.assertOwned();
                var assignments = groupUnmatched(unmatched, vectors, info, groups.values(), lease, deadlineNanos);
                for (String keyword : unmatched) {
                    if (System.nanoTime() >= deadlineNanos) break;
                    var group = assignments.get(keyword);
                    if (group != null) processed += contribute(keyword, group, pendingByKeyword.get(keyword), groups, lease);
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
        for (var group : store.groups(clock.millis())) {
            if (!eligible(group, info)) {
                lease.assertOwned();
                store.retire(group);
                continue;
            }
            groups.put(group.id(), group);
        }
        return groups;
    }

    private Map<String, List<HotSearchStore.Event>> loadPendingEvents(IdempotentGuard.Lease lease) {
        Map<String, List<HotSearchStore.Event>> pendingByKeyword = new LinkedHashMap<>();
        for (var event : store.pending(clock.millis())) {
            String keyword = filter.eligible(event.keyword());
            if (keyword == null) {
                lease.assertOwned();
                store.remove(event);
                continue;
            }
            var events = pendingByKeyword.computeIfAbsent(keyword, k -> new ArrayList<>());
            events.add(event);
        }
        return pendingByKeyword;
    }

    private Map<String, HotSearchGroup> resolveCachedGroups(List<String> keywords, QueryEmbeddingPort.Info info) {
        Map<String, HotSearchGroup> cachedGroups = new HashMap<>();
        for (String keyword : keywords) {
            var group = store.alias(keyword, info.version());
            if (!eligible(group, info)) group = store.named(keyword);
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
        for (var candidate : model.group(keywords)) {
            String name = filter.eligible(candidate.name());
            if (name != null) keywordsByName.computeIfAbsent(name, key -> new ArrayList<>()).addAll(candidate.keywords());
        }
        if (System.nanoTime() >= deadlineNanos) return Map.of();
        lease.assertOwned();
        Map<String, HotSearchGroup> groupsByName = new HashMap<>();
        for (var group : groups) groupsByName.put(group.name(), group);
        int newGroupLimit = Math.min(properties.getMaxNewGroupsPerRound(), properties.getGroupLimit() - groups.size());
        List<String> newNames = keywordsByName.keySet().stream().filter(name -> !groupsByName.containsKey(name))
                .limit(newGroupLimit).toList();
        List<String> missingVectors = newNames.stream().filter(name -> !vectors.containsKey(name)).toList();
        Map<String, List<Float>> nameVectors = Map.of();
        if (!missingVectors.isEmpty()) {
            try {
                nameVectors = model.vectors(missingVectors, info);
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
                           Map<String, HotSearchGroup> groups, IdempotentGuard.Lease lease) {
        int processed = 0;
        try {
            boolean contributed = false;
            for (var event : events) {
                lease.assertOwned();
                if (store.contribute(group, event, clock.millis())) contributed = true;
                store.remove(event);
                processed++;
            }
            if (contributed) {
                groups.put(group.id(), group);
                lease.assertOwned();
                store.remember(keyword, group);
            }
        } catch (RuntimeException error) {
            log.warn("Hot search item deferred keywordId={} version={}", HotSearchStore.digest(keyword), group.version(), error);
        }
        return processed;
    }

    private int publishRankings(Collection<HotSearchGroup> groups, IdempotentGuard.Lease lease) {
        long now = clock.millis();
        List<HotSearchStore.Ranking> rankings = new ArrayList<>();
        for (var group : groups) {
            lease.assertOwned();
            var ranking = store.ranking(group, now);
            if (ranking.score() == 0) store.retire(group);
            else if (ranking.users() >= properties.getMinUsers()) rankings.add(ranking);
        }
        List<String> names = rankings.stream().sorted(Comparator.comparingInt(HotSearchStore.Ranking::score).reversed()
                        .thenComparing(value -> value.group().id()))
                .map(value -> value.group().name()).distinct().limit(7).toList();
        lease.assertOwned();
        store.publish(names);
        return names.size();
    }

    private boolean eligible(HotSearchGroup group, QueryEmbeddingPort.Info info) {
        return group != null && group.valid(info.version(), info.dims()) && filter.eligible(group.name()) != null;
    }
}
