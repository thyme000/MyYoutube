package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "hot.redis.port", matches = "[0-9]+")
@Timeout(15)
class HotSearchRedisTest {
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    HotSearchStore store;
    HotSearchProperties properties;
    HotSearchModel model;
    HotSearchService service;
    ThreadPoolTaskExecutor executor;
    ThreadPoolTaskScheduler scheduler;
    IdempotentGuard guard;
    long now;

    @BeforeEach void setup() throws Exception {
        int port = Integer.getInteger("hot.redis.port");
        assertTrue(port > 10000, "仅使用隔离测试 Redis");
        connection = new LettuceConnectionFactory("127.0.0.1", port); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); clean();
        properties = new HotSearchProperties(); properties.setMinUsers(1);
        store = new HotSearchStore(redis, properties);
        executor = new HotSearchConfiguration().hotSearchCacheExecutor();
        scheduler = new ThreadPoolTaskScheduler(); scheduler.initialize();
        guard = new IdempotentGuard(redis, scheduler);
        now = System.currentTimeMillis();
        model = mock(HotSearchModel.class);
        when(model.info()).thenReturn(new QueryEmbeddingPort.Info("v1", 2));
        when(model.vectors(anyList(), any())).thenAnswer(call -> {
            Map<String, List<Float>> result = new HashMap<>();
            for (String text : (List<String>) call.getArgument(0)) result.put(text, text.contains("热量") ? List.of(0f, 1f) : List.of(1f, 0f));
            return result;
        });
        when(model.group(anyList())).thenAnswer(call -> grouped(call.getArgument(0)));
        service = new HotSearchService(store, model, new HotSearchFilter(properties), properties, guard, executor, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }

    List<HotSearchModel.Grouping> grouped(List<String> keywords) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String keyword : keywords) {
            String name = keyword.contains("热量") ? "番茄炒蛋热量" : "番茄炒蛋教程";
            groups.computeIfAbsent(name, key -> new ArrayList<>()).add(keyword);
        }
        return groups.entrySet().stream().map(entry -> new HotSearchModel.Grouping(entry.getKey(), entry.getValue())).toList();
    }

    void clean() {
        var keys = redis.keys(HotSearchStore.PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        redis.delete("idempotent:{hot-search}:refresh");
    }

    @AfterEach void cleanup() {
        if (executor != null) executor.shutdown();
        if (scheduler != null) scheduler.shutdown();
        if (redis != null) clean();
        if (connection != null) connection.destroy();
    }

    HotSearchStore.Event event(String text, long user, long time) {
        return new HotSearchStore.Event(UUID.randomUUID().toString(), text, user, time);
    }
    HotSearchGroup group(String id, String name) { return new HotSearchGroup(id, name, List.of(1f, 0f), "v1"); }
    void add(String text, long user) { assertTrue(store.record(event(text, user, now))); }

    @Test void groupsSynonymsDeduplicatesUsersAndDeletesOnlyProcessedInputs() {
        add("番茄炒蛋怎么做", 1); add("西红柿炒鸡蛋教程", 1); add("西红柿炒鸡蛋教程", 2);
        add("番茄炒蛋热量", 3);
        service.refresh();
        assertTrue(store.pending(now).isEmpty());
        assertEquals(List.of("番茄炒蛋教程", "番茄炒蛋热量"), service.hot());
        var group = store.named("番茄炒蛋教程");
        assertEquals(2, store.ranking(group, now).score());
        assertEquals(2, store.ranking(group, now).users());
        assertEquals(group.id(), store.alias("西红柿炒鸡蛋教程", "v1").id());
        verify(model).group(anyList());
        assertNull(store.named("西红柿炒鸡蛋教程"));
    }

    @Test void aliasHitSkipsAllInferenceButStillCountsANewUser() {
        add("西红柿炒鸡蛋教程", 1); service.refresh();
        clearInvocations(model);
        add("西红柿炒鸡蛋教程", 2); service.refresh();
        verify(model, never()).group(anyList());
        verify(model, never()).vectors(anyList(), any());
        assertEquals(2, store.ranking(store.named("番茄炒蛋教程"), now).score());
    }

    @Test void aliasAndSnapshotExpireWithoutASeparateCleanupJob() throws Exception {
        properties.setAliasTtlSeconds(1); properties.setSnapshotTtlSeconds(1);
        var group = group("expiry", "短期缓存");
        store.contribute(group, event("词", 1, now), now);
        store.remember("别名", group); store.publish(List.of(group.name()));
        assertNotNull(store.alias("别名", "v1")); assertEquals(List.of(group.name()), store.hot());
        Thread.sleep(1200);
        assertNull(store.alias("别名", "v1")); assertTrue(store.hot().isEmpty());
        assertNotNull(store.group(group.id()));
    }

    @Test void expiredAliasAndMissingGroupAreCacheMisses() {
        var group = group("old", "旧组"); store.contribute(group, event("旧词", 1, now), now);
        store.remember("别名", group);
        assertNotNull(store.alias("别名", "v1"));
        assertNull(store.alias("别名", "v2"));
        redis.delete(HotSearchStore.PREFIX + "alias:" + HotSearchStore.digest("别名"));
        assertNull(store.alias("别名", "v1"));
        store.remember("别名", group); store.retire(group);
        assertNull(store.alias("别名", "v1"));
        add("别名", 2); service.refresh();
        verify(model).vectors(List.of("别名"), new QueryEmbeddingPort.Info("v1", 2));
    }

    @Test void preservesEventHourAndCountsTheRollingBoundaryExactly() {
        var group = group("window", "窗口测试");
        long hour = Duration.ofHours(1).toMillis();
        long start = Math.floorDiv(now, hour) * hour;
        assertTrue(store.contribute(group, event("词", 1, start), now));
        assertTrue(store.contribute(group, event("词", 1, start), now));
        assertTrue(store.contribute(group, event("词", 1, start - hour), now));
        assertEquals(2, store.ranking(group, now).score());
        assertEquals(1, store.ranking(group, now).users());
        long boundary = now - HotSearchStore.WINDOW_MILLIS + 10000;
        assertTrue(store.contribute(group, event("词", 2, boundary), now));
        assertEquals(3, store.ranking(group, now).score());
        assertEquals(2, store.ranking(group, now + 10000).score());
        assertFalse(store.contribute(group, event("词", 3, now - HotSearchStore.WINDOW_MILLIS), now));
    }

    @Test void oldReplayDoesNotExtendContributionLifetime() {
        var group = group("ttl", "期限测试");
        store.contribute(group, event("词", 1, now - 10000), now);
        store.contribute(group, event("词", 1, now - 5000), now);
        store.contribute(group, event("词", 1, now - 10000), now);
        long ttl = redis.getExpire(HotSearchStore.PREFIX + "users:ttl", TimeUnit.MILLISECONDS);
        assertTrue(ttl <= HotSearchStore.WINDOW_MILLIS - 5000);
        assertTrue(ttl > HotSearchStore.WINDOW_MILLIS - 10000);
    }

    @Test void enforcesInputGroupContributionAndAliasCapacity() {
        properties.setPendingLimit(1); properties.setGroupLimit(1); properties.setContributionLimit(1);
        add("第一个", 1); assertFalse(store.record(event("第二个", 2, now)));
        var group = group("capacity", "容量测试");
        assertTrue(store.contribute(group, event("词", 1, now), now));
        assertFalse(store.contribute(group, event("词", 2, now), now));
        assertTrue(store.contribute(group, event("词", 1, now), now));
        assertFalse(store.contribute(group("other", "其他组"), event("词", 1, now), now));
        store.remember("第一别名", group); store.remember("第二别名", group);
        assertNotNull(store.alias("第一别名", "v1")); assertNull(store.alias("第二别名", "v1"));
    }

    @Test void failedModelsRetainInputsAndExistingSnapshot() {
        store.publish(List.of("旧榜")); add("新词", 1);
        when(model.vectors(anyList(), any())).thenThrow(new IllegalStateException("model unavailable"));
        service.refresh();
        assertEquals(1, store.pending(now).size()); assertEquals(List.of("旧榜"), service.hot());
    }

    @Test void minimumUsersUsesUnionAcrossHoursAndInvalidNamesNeverPublish() {
        properties.setMinUsers(2);
        var group = group("threshold", "人数测试");
        store.contribute(group, event("词", 1, now), now);
        store.contribute(group, event("词", 1, now - Duration.ofHours(1).toMillis()), now);
        service.refresh(); assertTrue(service.hot().isEmpty());
        store.contribute(group, event("词", 2, now), now);
        service.refresh(); assertEquals(List.of("人数测试"), service.hot());
        add("新组热量", 3);
        when(model.group(List.of("新组热量"))).thenReturn(List.of(new HotSearchModel.Grouping(".,.,", List.of("新组热量"))));
        service.refresh(); assertEquals(1, store.pending(now).size());
        assertNull(store.named(".,.,"));
    }

    @Test void currentVersionChangeRetiresGroupsAndIgnoresOldAliases() {
        add("番茄炒蛋怎么做", 1); service.refresh();
        String oldId = store.named("番茄炒蛋教程").id();
        when(model.info()).thenReturn(new QueryEmbeddingPort.Info("v2", 2));
        add("番茄炒蛋怎么做", 2); service.refresh();
        assertNull(store.group(oldId));
        assertEquals("v2", store.alias("番茄炒蛋怎么做", "v2").version());
        assertEquals(1, store.ranking(store.named("番茄炒蛋教程"), now).score());
    }

    @Test void failureAfterContributionKeepsInputAndRetryDoesNotDoubleCount() throws Exception {
        var observed = spy(store);
        var failed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (failed.compareAndSet(false, true)) throw new IllegalStateException("reply lost after write");
            return result;
        }).when(observed).contribute(any(), any(), anyLong());
        service = new HotSearchService(observed, model, new HotSearchFilter(properties), properties, guard,
                executor, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
        add("番茄炒蛋怎么做", 1); service.refresh();
        assertEquals(1, store.pending(now).size());
        service.refresh();
        assertTrue(store.pending(now).isEmpty());
        assertEquals(1, store.ranking(store.named("番茄炒蛋教程"), now).score());
    }

    @Test void newGroupBudgetDefersExcessGroupsToNextRound() {
        properties.setMaxNewGroupsPerRound(1);
        add("番茄炒蛋怎么做", 1); add("番茄炒蛋热量", 2);
        service.refresh(); assertEquals(1, store.pending(now).size());
        verify(model).group(anyList());
        service.refresh(); assertTrue(store.pending(now).isEmpty());
        assertEquals(2, service.hot().size());
    }

    @Test void rawSearchNamesCannotBypassOutputFiltersOrSnapshotExpiry() {
        store.publish(List.of("1111", "正常词")); assertEquals(List.of("正常词"), service.hot());
        redis.delete(HotSearchStore.PREFIX + "snapshot"); assertTrue(service.hot().isEmpty());
    }

    @Test void concurrentNewRecordsSurviveTheCurrentBatch() {
        add("番茄炒蛋怎么做", 1);
        when(model.group(anyList())).thenAnswer(call -> {
            store.record(event("下一批搜索", 9, now)); return grouped(call.getArgument(0));
        });
        service.refresh();
        assertEquals(List.of("下一批搜索"), store.pending(now).stream().map(HotSearchStore.Event::keyword).toList());
    }
}
