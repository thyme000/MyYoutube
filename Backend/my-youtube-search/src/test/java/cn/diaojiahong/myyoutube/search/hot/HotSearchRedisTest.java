package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisConnectionUtils;
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
    HotSearchHistoryStore history;
    HotSearchService service;
    ThreadPoolTaskExecutor executor;
    ThreadPoolTaskScheduler scheduler;
    IdempotentGuard guard;
    long now;

    @BeforeEach void setup() throws Exception {
        int port = Integer.getInteger("hot.redis.port");
        assertTrue(port > 10000, "仅使用隔离测试 Redis");
        connection = connection(port); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); clean();
        properties = new HotSearchProperties(); properties.setMinUsers(1);
        store = new HotSearchStore(redis, properties);
        executor = new HotSearchConfiguration().hotSearchCacheExecutor();
        scheduler = new ThreadPoolTaskScheduler(); scheduler.initialize();
        guard = new IdempotentGuard(redis, scheduler);
        now = System.currentTimeMillis();
        model = mock(HotSearchModel.class);
        history = mock(HotSearchHistoryStore.class);
        when(model.info()).thenReturn(new QueryEmbeddingPort.Info("v1", 2));
        when(model.vectors(anyList(), any())).thenAnswer(call -> {
            Map<String, List<Float>> result = new HashMap<>();
            for (String text : (List<String>) call.getArgument(0)) result.put(text, text.contains("热量") ? List.of(0f, 1f) : List.of(1f, 0f));
            return result;
        });
        when(model.group(anyList())).thenAnswer(call -> grouped(call.getArgument(0)));
        service = new HotSearchService(store, history, model, new HotSearchFilter(properties), properties, guard, executor, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
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
        var partitioned = redis.keys("{hs:*"); if (partitioned != null) partitioned.forEach(redis::delete);
        var names = redis.keys("hs:name:*"); if (names != null) names.forEach(redis::delete);
        redis.delete("idempotent:{hot-search}:refresh");
    }

    static LettuceConnectionFactory connection(int port) {
        String nodes = System.getProperty("hot.redis.cluster.nodes");
        if (nodes == null) return new LettuceConnectionFactory("127.0.0.1", port);
        var addresses = Arrays.asList(nodes.split(","));
        assertTrue(addresses.stream().allMatch(address -> address.startsWith("127.0.0.1:")
                && Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)) > 10000));
        return new LettuceConnectionFactory(new RedisClusterConfiguration(addresses));
    }

    @AfterEach void cleanup() {
        if (executor != null) executor.shutdown();
        if (scheduler != null) scheduler.shutdown();
        if (redis != null) clean();
        if (connection != null) connection.destroy();
    }

    HotSearchStore.Event event(String text, long user, long time) {
        return new HotSearchStore.Event(UUID.randomUUID().toString(), text, text, user, time);
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
        redis.delete(store.aliasKey("别名"));
        assertNull(store.alias("别名", "v1"));
        store.remember("别名", group); store.retire(group, now);
        assertNull(store.alias("别名", "v1"));
        add("别名", 2); service.refresh();
        verify(model).vectors(List.of("别名"), new QueryEmbeddingPort.Info("v1", 2));
    }

    @Test void deduplicatesByEventHourAndKeepsTheOldestOverlappingHourUntilItsEnd() {
        var group = group("window", "窗口测试");
        long hour = HotSearchStore.HOUR_MILLIS;
        long start = Math.floorDiv(now, hour) * hour;
        long at = start + hour / 2;
        assertTrue(store.contribute(group, List.of(event("词", 1, start), event("词", 1, start),
                event("词", 1, start - hour)), at));
        assertEquals(2, store.ranking(group, at).score());
        assertEquals(1, store.ranking(group, at).users());
        long boundary = at - HotSearchStore.WINDOW_MILLIS + 10000;
        assertTrue(store.contribute(group, event("词", 2, boundary), at));
        assertEquals(3, store.ranking(group, at).score());
        assertEquals(3, store.ranking(group, at + 10000).score());
        assertEquals(2, store.ranking(group, start + hour).score());
        assertEquals(1, store.ranking(group, start + hour).users());
        assertFalse(store.contribute(group, event("词", 3, at - HotSearchStore.WINDOW_MILLIS), at));
        assertFalse(store.contribute(group, event("词", 3, at + 1), at));
    }

    @Test void oldReplayDoesNotExtendContributionLifetime() {
        var group = group("ttl", "期限测试");
        long hour = Math.floorDiv(now, HotSearchStore.HOUR_MILLIS);
        var old = event("词", 1, (hour - 2) * HotSearchStore.HOUR_MILLIS);
        store.contribute(group, old, now);
        store.contribute(group, event("词", 1, hour * HotSearchStore.HOUR_MILLIS), now);
        store.contribute(group, old, now);
        long expiry = (hour + 1) * HotSearchStore.HOUR_MILLIS + HotSearchStore.WINDOW_MILLIS;
        assertEquals(expiry - 2 * HotSearchStore.HOUR_MILLIS, expiresAt(store.hourKey(group.id(), hour - 2)));
        assertEquals(expiry, expiresAt(store.hourKey(group.id(), hour)));
        assertEquals(expiry, expiresAt(store.groupKey(group.id())));
        assertEquals(expiry, expiresAt(store.nameKey(group.name())));
        assertEquals((double) ((hour + 1) * HotSearchStore.HOUR_MILLIS), redis.opsForZSet().score(HotSearchStore.PREFIX + "active", group.id()));
    }

    long expiresAt(String key) {
        return redis.execute(org.springframework.data.redis.core.script.RedisScript.of(
                "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key));
    }

    @Test void enforcesInputAndAliasCapacityWithoutLimitingGroupContributions() {
        properties.setPartitionCount(1);
        store = new HotSearchStore(redis, properties);
        properties.setPendingLimit(1);
        add("第一个", 1); assertFalse(store.record(event("第二个", 2, now)));
        var group = group("capacity", "容量测试");
        assertTrue(store.contribute(group, event("词", 1, now), now));
        assertTrue(store.contribute(group, event("词", 2, now), now));
        assertTrue(store.contribute(group, event("词", 1, now), now));
        assertEquals(2, store.ranking(group, now).score());
        store.remember("第一别名", group); store.remember("第二别名", group);
        assertNotNull(store.alias("第一别名", "v1")); assertNull(store.alias("第二别名", "v1"));
    }

    @Test void countsBeyondTenThousandUserHoursAndReplayKeepsTheSameEstimate() {
        var target = group("large-count", "热门主题");
        List<HotSearchStore.Event> events = new ArrayList<>();
        for (int i = 0; i < 12000; i++) events.add(event("词", 2_000_000_000_000_000_000L + i, now));

        assertTrue(store.contribute(target, events, now));
        var first = store.ranking(target, now);
        assertTrue(first.score() > 10000);
        assertEquals(first.score(), first.users());
        assertTrue(store.contribute(target, events, now));
        assertEquals(first, store.ranking(target, now));
    }

    @Test @Timeout(45)
    void loadsEveryPageWithoutSkippingMissingMetadataAndCreatesGroupsBeyondFiveHundred() {
        List<HotSearchGroup> active = new ArrayList<>();
        // 大量夹具写入复用连接；待验证的读取及 refresh 仍使用默认连接策略。
        RedisConnectionUtils.bindConnection(connection);
        try {
            for (int i = 0; i < 503; i++) {
                var target = new HotSearchGroup("existing-" + i, "已有主题" + i, List.of(0f, 1f), "v1");
                active.add(target);
                assertTrue(store.contribute(target, event("词", i, now), now));
            }
        } finally {
            RedisConnectionUtils.unbindConnection(connection);
        }
        redis.delete(store.groupKey(active.get(0).id()));
        redis.delete(store.groupKey(active.get(250).id()));
        var last = active.get(502);
        assertEquals(501, store.groups(now).size());
        assertTrue(store.groups(now).contains(last));
        store.contribute(last, event("词", 9999, now), now);
        add("番茄炒蛋怎么做", 9999);

        service.refresh();

        assertTrue(store.pending(now).isEmpty());
        assertNotNull(store.named("番茄炒蛋教程"));
        assertEquals(502, store.groups(now).size());
        assertTrue(service.hot().contains(last.name()));
        verify(history).append(argThat(batch -> batch.size() == 1));
    }

    @Test void retiringAGroupRemovesAllHoursBeforeReusingItsId() {
        var target = group("reuse", "重新归组");
        store.contribute(target, List.of(event("词", 1, now),
                event("词", 2, now - 2 * HotSearchStore.HOUR_MILLIS)), now);
        assertEquals(2, store.ranking(target, now).score());

        store.retire(target, now);

        assertEquals(0, store.ranking(target, now).score());
        assertTrue(store.contribute(target, event("词", 3, now), now));
        assertEquals(1, store.ranking(target, now).score());
    }

    @Test void missingGroupMetadataIsACacheMissAndDoesNotOccupyTheActiveIndex() {
        var missing = group("missing-meta", "缺失组");
        var existing = group("existing-meta", "有效组");
        store.contribute(missing, event("词", 1, now), now);
        store.contribute(existing, event("词", 2, now), now);
        redis.delete(store.groupKey(missing.id()));

        assertNull(store.named(missing.name()));
        assertEquals(List.of(existing), store.groups(now));
        assertNull(redis.opsForZSet().score(HotSearchStore.PREFIX + "active", missing.id()));
        assertEquals(existing, store.named(existing.name()));
    }

    @Test void indexFailureDoesNotUndoLocalContributionsAndReplayDoesNotDoubleCount() {
        var target = group("index-failure", "索引故障");
        var input = event("词", 1, now);
        redis.opsForValue().set(HotSearchStore.PREFIX + "active", "wrong-type");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> store.contribute(target, input, now));
        assertEquals(1, store.ranking(target, now).score());
        assertEquals(target, store.group(target.id()));
        redis.delete(HotSearchStore.PREFIX + "active");

        assertTrue(store.contribute(target, input, now));

        assertEquals(1, store.ranking(target, now).score());
        assertEquals(List.of(target), store.groups(now));
        assertEquals(target, store.named(target.name()));
    }

    @Test
    @EnabledIfSystemProperty(named = "hot.redis.cluster.nodes", matches = ".+")
    void groupContributionsAreColocatedPerGroupAndDistributedAcrossPrimaries() {
        List<HotSearchGroup> groups = new ArrayList<>();
        try (var cluster = connection.getClusterConnection()) {
            Set<String> owners = new HashSet<>();
            for (int i = 0; i < 33; i++) {
                var target = group("distributed-" + i, "分布组" + i);
                groups.add(target);
                assertTrue(store.contribute(target, List.of(event("词", i, now)), now));
                byte[] meta = store.groupKey(target.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] users = store.hourKey(target.id(), Math.floorDiv(now, HotSearchStore.HOUR_MILLIS)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(cluster.clusterGetSlotForKey(meta), cluster.clusterGetSlotForKey(users));
                owners.add(cluster.clusterGetNodeForKey(meta).getId());
            }
            assertTrue(owners.size() > 1, "组数据不能全部集中在一个主节点");
        }
        assertEquals(new HashSet<>(groups), new HashSet<>(store.groups(now)));
        assertTrue(store.rankings(groups, now).stream().allMatch(value -> value.score() == 1 && value.users() == 1));
        store.retire(groups, now);
        assertTrue(store.groups(now).isEmpty());
        for (var target : groups) {
            assertNull(store.group(target.id()));
            assertEquals(0, store.ranking(target, now).score());
            assertNull(store.named(target.name()));
        }
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
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(history).append(anyList());
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

    @Test void pendingRetentionIsIndependentOfTheOnlineWindowAndDoesNotKeepExpiredInputs() {
        var old = event("昨日搜索", 1, now - Duration.ofHours(28).toMillis());
        assertTrue(store.record(old));
        assertEquals(List.of(old), store.pending(now));
        var target = group("old-history", "历史搜索");
        assertFalse(store.contribute(target, old, now));
        assertTrue(store.pending(old.happenedAt() + HotSearchStore.PENDING_MILLIS).isEmpty());
    }

    @Test void fixedAssignmentSurvivesGroupRetirementAndCannotBeOverwritten() {
        var event = event("  真实输入  ", 1, now);
        var target = group("fixed", "原组名");
        assertTrue(store.record(event));
        var assigned = store.assign(event, target, now);
        store.retire(target, now);
        assertEquals(assigned, store.assign(event, group("other", "另一组"), now));
        assertEquals(assigned, store.assignment(event));
        assertEquals(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate().toString(), assigned.statDate());
        assertTrue(redis.getExpire(store.assignmentKey(event), TimeUnit.MILLISECONDS) <= HotSearchStore.PENDING_MILLIS);
        store.remove(event);
        assertNull(store.assignment(event));
        assertTrue(store.pending(now).isEmpty());
    }

    @Test void batchAssignmentsKeepExistingOwnersAndAcknowledgmentPreservesOtherInputs() {
        properties.setPartitionCount(1);
        store = new HotSearchStore(redis, properties);
        properties.setBatchSize(300);
        var original = group("original", "原归属");
        var proposed = group("proposed", "新归属");
        var events = new ArrayList<HotSearchStore.Event>();
        for (int i = 0; i < 201; i++) {
            var event = event("批量输入", i, now);
            assertTrue(store.record(event));
            events.add(event);
        }
        var fixed = store.assign(events.get(0), original, now);
        var assignments = store.assign(proposed, events, now);
        assertEquals(fixed, assignments.get(events.get(0)));
        assertEquals(200, assignments.values().stream().filter(value -> proposed.id().equals(value.groupId())).count());
        var concurrent = event("新到输入", 999, now);
        assertTrue(store.record(concurrent));
        store.assign(concurrent, original, now);

        store.remove(events);

        assertEquals(List.of(concurrent), store.pending(now));
        assertTrue(store.assignments(events).isEmpty());
        assertNotNull(store.assignment(concurrent));
        store.remove(events);
        assertEquals(List.of(concurrent), store.pending(now));
    }

    HotSearchStore.Event eventInPartition(int partition, int sequence) {
        for (int candidate = sequence; ; candidate++) {
            String token = "partition-" + partition + "-event-" + sequence + "-" + candidate;
            if (store.partition(token) == partition) return new HotSearchStore.Event(token, "同一个搜索词", "  同一个搜索词  ", sequence, now);
        }
    }

    @Test void closingBatchReadsOnlyBeforeMidnightAcrossEveryPartition() {
        properties.setPartitionCount(3); properties.setBatchSize(2);
        store = new HotSearchStore(redis, properties);
        long midnight = Instant.ofEpochMilli(now).atZone(HotSearchStore.STAT_ZONE).toLocalDate()
                .atStartOfDay(HotSearchStore.STAT_ZONE).toInstant().toEpochMilli();
        for (int partition = 0; partition < 3; partition++) {
            var old = eventInPartition(partition, 1);
            assertTrue(store.record(new HotSearchStore.Event(old.token(), old.keyword(), old.rawKeyword(), old.userId(), midnight - 1)));
            var current = eventInPartition(partition, 2);
            assertTrue(store.record(new HotSearchStore.Event(current.token(), current.keyword(), current.rawKeyword(), current.userId(), midnight)));
        }

        var first = store.pending(now, midnight);
        assertEquals(2, first.size());
        assertTrue(first.stream().allMatch(event -> event.happenedAt() < midnight));
        store.remove(first);
        var last = store.pending(now, midnight);
        assertEquals(1, last.size());
        store.remove(last);
        assertTrue(store.pending(now, midnight).isEmpty());
        properties.setBatchSize(10);
        assertEquals(3, store.pending(now).size());
        assertTrue(store.pending(now).stream().allMatch(event -> event.happenedAt() == midnight));
    }

    @Test void closingDateAndSummaryQueueAdvanceTogetherAndRetriesAreIdempotent() {
        var date = LocalDate.of(2026, 10, 20);
        assertNull(store.closedThrough());
        assertEquals(date.minusDays(1), store.initializeArchive(date.minusDays(1)));
        assertEquals(date.minusDays(1), store.initializeArchive(date.plusDays(10)));
        assertTrue(store.closeDay(date));
        assertFalse(store.closeDay(date));
        assertThrows(IllegalStateException.class, () -> store.closeDay(date.plusDays(2)));
        assertEquals(date, store.closedThrough());
        assertEquals(date, store.nextSummaryDate());
        assertTrue(store.closeDay(date.plusDays(1)));
        store.summaryCompleted(date);
        assertEquals(date.plusDays(1), store.nextSummaryDate());
        assertFalse(store.closeDay(date));
        store.summaryCompleted(date.plusDays(1));
        assertNull(store.nextSummaryDate());
        assertEquals(-1L, redis.getExpire(HotSearchStore.PREFIX + "archive:closed-through"));
    }

    @Test void failedSummaryRegistrationDoesNotAdvanceTheClosingDate() {
        var date = LocalDate.of(2026, 10, 20);
        store.initializeArchive(date.minusDays(1));
        redis.opsForValue().set(HotSearchStore.PREFIX + "archive:pending", "wrong-type");

        assertThrows(org.springframework.dao.DataAccessException.class, () -> store.closeDay(date));

        assertEquals(date.minusDays(1), store.closedThrough());
        redis.delete(HotSearchStore.PREFIX + "archive:pending");
        assertTrue(store.closeDay(date));
        assertEquals(date, store.nextSummaryDate());
    }

    @Test void dailyClosingUsesTheSameRedisLeaseAsTheNormalRefresh() {
        var due = Instant.ofEpochMilli(now).atZone(HotSearchStore.STAT_ZONE).toLocalDate().minusDays(1);
        store.initializeArchive(due.minusDays(1));
        var atFour = due.plusDays(1).atTime(4, 0).atZone(HotSearchStore.STAT_ZONE).toInstant();
        var archive = new HotSearchDailyArchive(store, service, history, properties, guard,
                Clock.fixed(atFour, ZoneOffset.UTC));

        try (var lease = guard.acquire("{hot-search}:refresh", IdempotentGuard.DEFAULT_LEASE_SECONDS)) {
            assertTrue(lease.acquired());
            archive.run();
            assertEquals(due.minusDays(1), store.closedThrough());
            assertNull(store.nextSummaryDate());
        }
        verifyNoInteractions(model, history);
    }

    @Test void pendingSharesItsBudgetAndRotatesPartitionsWithoutStarvation() {
        properties.setPartitionCount(3); properties.setBatchSize(5);
        store = new HotSearchStore(redis, properties);
        for (int partition = 0; partition < 3; partition++) {
            for (int i = 0; i < 8; i++) assertTrue(store.record(eventInPartition(partition, i)));
        }
        for (int round = 0; round < 3; round++) {
            var batch = store.pending(now);
            assertEquals(5, batch.size());
            final int start = round;
            for (int partition = 0; partition < 3; partition++) {
                final int expectedPartition = partition;
                int expected = partition == (start + 2) % 3 ? 1 : 2;
                assertEquals(expected, batch.stream().filter(event -> store.partition(event.token()) == expectedPartition).count());
            }
        }
        properties.setBatchSize(1);
        Set<Integer> visited = new HashSet<>();
        for (int i = 0; i < 3; i++) visited.add(store.partition(store.pending(now).get(0).token()));
        assertEquals(Set.of(0, 1, 2), visited);
    }

    @Test void busyPartitionBorrowsUnusedReadBudget() {
        properties.setPartitionCount(4); properties.setBatchSize(7);
        store = new HotSearchStore(redis, properties);
        for (int i = 0; i < 20; i++) assertTrue(store.record(eventInPartition(3, i)));
        var batch = store.pending(now);
        assertEquals(7, batch.size());
        assertEquals(7, new HashSet<>(batch).size());
        assertTrue(batch.stream().allMatch(event -> store.partition(event.token()) == 3));
    }

    @Test void partitionQuotasKeepTheOriginalTotalCapacity() {
        properties.setPartitionCount(3); properties.setPendingLimit(8);
        store = new HotSearchStore(redis, properties);
        var target = group("quota", "分区容量");
        int accepted = 0;
        for (int partition = 0; partition < 3; partition++) {
            int quota = partition < 2 ? 3 : 2;
            for (int i = 0; i < quota; i++) {
                assertTrue(store.record(eventInPartition(partition, i)));
                accepted++;
                String alias = aliasInPartition(partition, i);
                store.remember(alias, target);
                assertEquals(target.id(), store.aliases(List.of(alias), "v1").get(alias));
            }
            assertFalse(store.record(eventInPartition(partition, quota)));
            String overflow = aliasInPartition(partition, quota);
            store.remember(overflow, target);
            assertFalse(store.aliases(List.of(overflow), "v1").containsKey(overflow));
        }
        assertEquals(8, accepted);
        assertEquals(8, store.pending(now).size());
    }

    String aliasInPartition(int partition, int sequence) {
        for (int candidate = sequence; ; candidate++) {
            String text = "别名-" + partition + "-" + sequence + "-" + candidate;
            if (store.partition(HotSearchStore.digest(text)) == partition) return text;
        }
    }

    @Test void configurationRefreshCannotChangeTheRoutingOfAnExistingStore() {
        properties.setPartitionCount(3);
        store = new HotSearchStore(redis, properties);
        var input = eventInPartition(2, 1);
        assertTrue(store.record(input));
        String before = store.assignmentKey(input);
        properties.setPartitionCount(7);
        assertEquals(before, store.assignmentKey(input));
        assertEquals(List.of(input), store.pending(now));
        store.assign(input, group("fixed-layout", "固定路由"), now);
        store.remove(input);
        assertTrue(store.pending(now).isEmpty());
    }

    @Test void partialPartitionAcknowledgmentCanBeRetriedWithoutDeletingNewInputs() {
        properties.setPartitionCount(3);
        store = new HotSearchStore(redis, properties);
        var inputs = List.of(eventInPartition(0, 1), eventInPartition(1, 1), eventInPartition(2, 1));
        inputs.forEach(input -> assertTrue(store.record(input)));
        store.assign(group("ack-partitions", "确认分区"), inputs, now);
        var failingRedis = spy(redis);
        doThrow(new IllegalStateException("partition unavailable")).when(failingRedis).execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                argThat(keys -> store.pendingKey(1).equals(keys.get(0))), any(Object[].class));
        var failing = new HotSearchStore(failingRedis, properties);
        assertThrows(IllegalStateException.class, () -> failing.remove(inputs));
        assertEquals(Set.of(inputs.get(1), inputs.get(2)), new HashSet<>(store.pending(now)));
        var concurrent = eventInPartition(0, 2);
        assertTrue(store.record(concurrent));
        store.remove(inputs);
        assertEquals(List.of(concurrent), store.pending(now));
        assertTrue(store.assignments(inputs).isEmpty());
    }

    @Test
    @EnabledIfSystemProperty(named = "hot.redis.cluster.nodes", matches = ".+")
    void partitionedDataUsesMultipleClusterPrimaries() {
        try (var cluster = connection.getClusterConnection()) {
            Set<String> owners = new HashSet<>();
            for (int partition = 0; partition < properties.getPartitionCount(); partition++) {
                var input = eventInPartition(partition, 1);
                assertTrue(store.record(input));
                byte[] pending = store.pendingKey(partition).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] assignment = store.assignmentKey(input).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(cluster.clusterGetSlotForKey(pending), cluster.clusterGetSlotForKey(assignment));
                owners.add(cluster.clusterGetNodeForKey(pending).getId());
            }
            assertTrue(owners.size() > 1, "输入不能仍全部集中在一个主节点");
        }
    }

    @Test void rankingBatchesKeepGroupOrderAndUserHourDeduplication() {
        List<HotSearchGroup> groups = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            var target = group("rank-" + i, "排名组" + i);
            groups.add(target);
            assertTrue(store.contribute(target, List.of(event("词", i, now), event("词", i, now),
                    event("词", i, now - Duration.ofHours(1).toMillis())), now));
        }
        var last = groups.get(groups.size() - 1);
        assertTrue(store.contribute(last, List.of(event("词", 999, now)), now));

        var rankings = store.rankings(groups, now);

        assertEquals(groups, rankings.stream().map(HotSearchStore.Ranking::group).toList());
        assertTrue(rankings.subList(0, 32).stream().allMatch(value -> value.score() == 2 && value.users() == 1));
        assertEquals(3, rankings.get(32).score());
        assertEquals(2, rankings.get(32).users());
    }

    @Test void samplesRedisRefreshForTwoHundredEvents() {
        var target = group("batch-sample", "番茄炒蛋教程");
        store.contribute(target, event(target.name(), 99999, now), now);
        long[] samples = new long[5];
        for (int round = 0; round < 7; round++) {
            for (int i = 0; i < 200; i++) {
                assertTrue(store.record(event(target.name(), round * 200L + i, now)));
            }
            long started = System.nanoTime();
            service.refresh();
            long elapsed = System.nanoTime() - started;
            assertTrue(store.pending(now).isEmpty());
            if (round >= 2) samples[round - 2] = TimeUnit.NANOSECONDS.toMillis(elapsed);
        }
        System.out.println("Hot search Redis sample events=200 elapsedMs=" + Arrays.toString(samples));
        Arrays.sort(samples);
        System.out.println("Hot search Redis sample medianMs=" + samples[2]);
        verify(model, never()).vectors(anyList(), any());
        verify(model, never()).group(anyList());
        verify(history, times(7)).append(argThat(batch -> batch.size() == 200));
    }
}
