package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotSearchRefreshTest {
    final long now = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli();

    final QueryEmbeddingPort.Info info = new QueryEmbeddingPort.Info("v1", 2);

    final HotSearchGroup group = new HotSearchGroup("existing", "已有组", List.of(1f, 0f), "v1");

    final HotSearchStore store = mock(HotSearchStore.class);

    final HotSearchHistoryStore history = mock(HotSearchHistoryStore.class);

    final HotSearchModel model = mock(HotSearchModel.class);

    final HotSearchProperties properties = new HotSearchProperties();

    final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    final HotSearchMetrics metrics = new HotSearchMetrics(registry);

    final IdempotentGuard guard = mock(IdempotentGuard.class);

    final List<HotSearchStore.Event> pending = new ArrayList<>();

    final Map<HotSearchStore.Event, HotSearchStore.Assignment> assignments = new LinkedHashMap<>();

    final Map<String, String> aliasIds = new LinkedHashMap<>();

    final Map<String, HotSearchStore.Ranking> rankings = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        properties.setMinUsers(1);
        IdempotentGuard.Lease lease = mock(IdempotentGuard.Lease.class);
        when(guard.acquire(anyString(), anyLong())).thenReturn(lease);
        when(lease.acquired()).thenReturn(true);
        when(model.info()).thenReturn(info);
        when(store.groups(now)).thenReturn(List.of(group));
        when(store.pending(now)).thenAnswer(call -> List.copyOf(pending));
        doAnswer(call -> {
            Collection<HotSearchStore.Event> events = call.getArgument(0);
            pending.removeAll(events);
            events.forEach(assignments::remove);
            return null;
        }).when(store).remove(anyCollection());
        when(store.assignments(anyList())).thenAnswer(call -> {
            List<HotSearchStore.Event> events = call.getArgument(0);
            Map<HotSearchStore.Event, HotSearchStore.Assignment> result = new LinkedHashMap<>();
            events.forEach(event -> {
                if (assignments.containsKey(event)) result.put(event, assignments.get(event));
            });
            return result;
        });
        when(store.assign(any(HotSearchGroup.class), anyList(), eq(now))).thenAnswer(call -> {
            HotSearchGroup target = call.getArgument(0, HotSearchGroup.class);
            List<HotSearchStore.Event> events = call.getArgument(1);
            Map<HotSearchStore.Event, HotSearchStore.Assignment> result = new LinkedHashMap<>();
            for (HotSearchStore.Event event : events) result.put(event, assignments.computeIfAbsent(event, key ->
                    new HotSearchStore.Assignment(target.id(), target.name(), target.version(),
                            Instant.ofEpochMilli(event.happenedAt())
                                    .atZone(ZoneId.of("Asia/Shanghai"))
                                    .toLocalDate()
                                    .toString())));
            return result;
        });
        when(store.aliases(anyList(), eq("v1"))).thenAnswer(call -> aliasIds);
        when(store.contribute(eq(group), anyList(), eq(now))).thenReturn(true);
        rankings.put(group.id(), new HotSearchStore.Ranking(group, 2, 1));
        when(store.rankings(anyCollection(), eq(now))).thenAnswer(call -> {
            Collection<HotSearchGroup> groups = call.getArgument(0);
            return groups.stream().map(target -> rankings.get(target.id())).toList();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 7, 10})
    void publishesRankedSnapshotUsingTheConfiguredTopLimit(int limit) {
        properties.setTopLimit(limit);
        List<HotSearchGroup> groups = IntStream.range(0, 12)
                .mapToObj(i -> new HotSearchGroup("g" + i, "正常主题" + i, List.of(1f, 0f), "v1")).toList();
        when(store.groups(now)).thenReturn(groups);
        for (int i = 0; i < groups.size(); i++) {
            HotSearchGroup target = groups.get(i);
            rankings.put(target.id(), new HotSearchStore.Ranking(target, 100 - i, 1));
        }

        service(model).refresh();

        verify(store).publish(groups.subList(0, limit).stream().map(HotSearchGroup::name).toList());
        assertEquals(12, registry.get(MetricNames.HOT_SEARCH_ACTIVE_GROUPS).gauge().value());
        assertEquals(limit, registry.get(MetricNames.HOT_SEARCH_DISPLAYED).gauge().value());
    }

    @Test
    void closedDatesMoveToTheFixedOpenDateWhileKeepingTheOriginalEventTime() {
        HotSearchStore.Event old = event("缓存词", 1);
        pending.add(old);
        aliasIds.put("缓存词", group.id());
        LocalDate closed = java.time.LocalDate.of(2026, 10, 21);
        when(store.closedThrough()).thenReturn(closed);
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(history).append(anyList());
        HotSearchService service = service(model);

        service.refresh();
        service.refresh();

        verify(history, times(2)).append(argThat(batch -> batch.size() == 1
                && batch.get(0).statDate().equals(closed.plusDays(1))
                && batch.get(0).happenedAt() == old.happenedAt()
                && batch.get(0).userId() == old.userId()));
        assertTrue(pending.isEmpty());
    }

    @Test
    void failedEmbeddingBatchStillProcessesCachedTermsAndRetriesOthersOnTheNextRefresh() {
        HotSearchStore.Event cached = event("缓存词", 1);
        HotSearchStore.Event unknown = event("新词", 2);
        pending.addAll(List.of(cached, unknown));
        aliasIds.put("缓存词", group.id());
        when(model.vectors(List.of("新词"), info)).thenThrow(new IllegalStateException("unavailable"));
        HotSearchService service = service(model);

        service.refresh();

        assertEquals(List.of(unknown), pending);
        verify(store).contribute(group, List.of(cached), now);
        verify(store, never()).contribute(group, List.of(unknown), now);
        verify(store).publish(List.of("已有组"));
        verify(model).vectors(List.of("新词"), info);
        verify(model, never()).group(anyList());

        doReturn(Map.of("新词", List.of(1f, 0f))).when(model).vectors(List.of("新词"), info);
        service.refresh();

        assertTrue(pending.isEmpty());
        verify(store).contribute(group, List.of(unknown), now);
        verify(model, times(2)).vectors(List.of("新词"), info);
    }

    @Test
    void databaseFailureKeepsFixedAssignmentsAndDoesNotBlockTheOnlineSnapshot() {
        HotSearchStore.Event event = event("缓存词", 1);
        pending.add(event);
        aliasIds.put("缓存词", group.id());
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(history).append(anyList());
        HotSearchService service = service(model);

        service.refresh();

        assertEquals(List.of(event), pending);
        assertEquals(1, registry.get(MetricNames.HOT_SEARCH_DURATION)
                .tags("operation", "history_write", "outcome", "failure")
                .timer()
                .count());
        assertEquals(
                0,
                registry.get(MetricNames.HOT_SEARCH_HISTORY_EVENTS).tag("outcome", "committed").counter().count());
        assertEquals(
                1,
                registry.get(MetricNames.HOT_SEARCH_HISTORY_EVENTS).tag("outcome", "deferred").counter().count());
        assertEquals(group.id(), assignments.get(event).groupId());
        verify(store).publish(List.of(group.name()));
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(event)));
        clearInvocations(model);
        when(model.info()).thenThrow(new IllegalStateException("model offline"));

        service.refresh();

        assertTrue(pending.isEmpty());
        ArgumentCaptor<List> batches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(history, times(2)).append(batches.capture());
        assertEquals(batches.getAllValues().get(0), batches.getAllValues().get(1));
        verify(model, never()).vectors(anyList(), any());
        verify(model, never()).group(anyList());
        verify(store, times(1)).contribute(group, List.of(event), now);
    }

    @Test
    void committedHistoryAndFailedAcknowledgmentAreObservedSeparatelyWithoutDeletingInput() {
        HotSearchStore.Event event = event("缓存词", 1);
        pending.add(event);
        aliasIds.put("缓存词", group.id());
        doThrow(new IllegalStateException("redis unavailable")).when(store).remove(anyCollection());

        service(model).refresh();

        assertEquals(List.of(event), pending);
        assertEquals(
                1,
                registry.get(MetricNames.HOT_SEARCH_HISTORY_EVENTS).tag("outcome", "committed").counter().count());
        assertEquals(
                0,
                registry.get(MetricNames.HOT_SEARCH_HISTORY_EVENTS).tag("outcome", "acknowledged").counter().count());
        assertEquals(1, registry.get(MetricNames.HOT_SEARCH_DURATION)
                .tags("operation", "history_ack", "outcome", "failure")
                .timer()
                .count());
        assertEquals(1, registry.get(MetricNames.HOT_SEARCH_DURATION)
                .tags("operation", "history_write", "outcome", "success")
                .timer()
                .count());
    }

    @Test
    void allResolvedKeywordsAreWrittenAsOneBoundedBatchBeforeInputsAreRemoved() {
        HotSearchStore.Event first = event("缓存词", 1);
        HotSearchStore.Event second = event("另一个缓存词", 2);
        pending.addAll(List.of(first, second));
        aliasIds.put(first.keyword(), group.id());
        aliasIds.put(second.keyword(), group.id());

        service(model).refresh();

        ArgumentCaptor<List> batches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(history).append(batches.capture());
        assertEquals(2, batches.getValue().size());
        InOrder order = inOrder(history, store);
        order.verify(history).append(anyList());
        order.verify(store).remove(anyCollection());
    }

    @Test
    void acknowledgmentFailureRetriesTheSameEventWithoutRegrouping() {
        HotSearchStore.Event event = event("缓存词", 1);
        pending.add(event);
        aliasIds.put("缓存词", group.id());
        doThrow(new IllegalStateException("ack unavailable")).doAnswer(call -> {
            pending.remove(event);
            assignments.remove(event);
            return null;
        }).when(store).remove(anyCollection());
        HotSearchService service = service(model);

        service.refresh();
        assertEquals(List.of(event), pending);
        clearInvocations(model);
        service.refresh();

        assertTrue(pending.isEmpty());
        ArgumentCaptor<List> batches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(history, times(2)).append(batches.capture());
        assertEquals(batches.getAllValues().get(0), batches.getAllValues().get(1));
        verify(model, never()).vectors(anyList(), any());
        verify(model, never()).group(anyList());
    }

    @Test
    void duplicateIndexOnlyDefersItsOwnEventsAndRemainingInputIsReindexedNextRound() {
        HotSearchStore.Event bad = event("坏项", 1);
        HotSearchStore.Event good = event("好项", 2);
        pending.addAll(List.of(bad, good));
        QueryEmbeddingPort queries = mock(QueryEmbeddingPort.class);
        LlmPort llm = mock(LlmPort.class);
        when(queries.info(any())).thenReturn(info);
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(List.of(
                        new LlmPort.EmbeddingItem(0, List.of(1f, 0f), null),
                        new LlmPort.EmbeddingItem(1, List.of(1f, 0f), null),
                        new LlmPort.EmbeddingItem(0, List.of(0f, 1f), null)), 2, "v1", null))
                .thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1f, 0f)), 2, "v1"));
        HotSearchService service = service(new HotSearchModel(llm, queries, properties, new PromptTemplates()));

        service.refresh();

        assertEquals(List.of(bad), pending);
        verify(store).contribute(group, List.of(good), now);
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(bad)));

        service.refresh();

        assertTrue(pending.isEmpty());
        verify(store).contribute(group, List.of(bad), now);
        ArgumentCaptor<LlmPort.EmbeddingsRequest> requests = org.mockito.ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm, times(2)).embeddings(requests.capture(), any());
        assertEquals(List.of("坏项", "好项"), requests.getAllValues().get(0).getTexts());
        assertEquals(List.of("坏项"), requests.getAllValues().get(1).getTexts());
        verify(llm, never()).chat(any(), any());
    }

    @Test
    void failedBatchWithNoCacheHitsRetainsAllInputsAndTheExistingSnapshot() {
        HotSearchStore.Event event = event("新词", 1);
        pending.add(event);
        when(model.vectors(anyList(), any())).thenThrow(new IllegalStateException("unavailable"));

        service(model).refresh();

        assertEquals(List.of(event), pending);
        verify(store, never()).remove(anyCollection());
        verify(store, never()).publish(anyList());
        verify(model).vectors(List.of("新词"), info);
    }

    @Test
    void incompatibleResponseMetadataStillAllowsCacheHitsButDefersUnknownTerms() {
        HotSearchStore.Event cached = event("缓存词", 1);
        HotSearchStore.Event unknown = event("新词", 2);
        pending.addAll(List.of(cached, unknown));
        aliasIds.put("缓存词", group.id());
        QueryEmbeddingPort queries = mock(QueryEmbeddingPort.class);
        LlmPort llm = mock(LlmPort.class);
        when(queries.info(any())).thenReturn(info);
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(List.of(1f, 0f)),
                2,
                "v2"));

        service(new HotSearchModel(llm, queries, properties, new PromptTemplates())).refresh();

        assertEquals(List.of(unknown), pending);
        verify(store).contribute(group, List.of(cached), now);
        verify(store, never()).contribute(group, List.of(unknown), now);
        verify(llm, never()).chat(any(), any());
    }

    @Test
    void groupingFailureRetainsAllUnmatchedTermsAndStillProcessesCacheHits() {
        HotSearchStore.Event failed = event("需要命名", 1);
        HotSearchStore.Event second = event("另一个主题", 2);
        HotSearchStore.Event cached = event("缓存词", 3);
        pending.addAll(List.of(failed, second, cached));
        aliasIds.put("缓存词", group.id());
        when(model.vectors(List.of("需要命名", "另一个主题"), info)).thenReturn(Map.of(
                "需要命名", List.of(0f, 1f), "另一个主题", List.of(0f, 1f)));
        when(model.group(List.of("需要命名", "另一个主题"))).thenThrow(new IllegalStateException("invalid JSON"));

        service(model).refresh();

        assertEquals(List.of(failed, second), pending);
        verify(store).contribute(group, List.of(cached), now);
        verify(model).group(List.of("需要命名", "另一个主题"));
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(failed)));
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(second)));
        verify(store).publish(List.of("已有组"));
    }

    @Test
    void submitsOnlyUnmatchedTermsTogetherAndEmbedsAllNewNamesInOneBatch() {
        HotSearchStore.Event first = event("首个搜索词", 1);
        HotSearchStore.Event second = event("同义搜索词", 2);
        HotSearchStore.Event other = event("另一主题", 3);
        HotSearchStore.Event matched = event("已知主题", 4);
        HotSearchStore.Event cached = event("缓存词", 5);
        pending.addAll(List.of(first, second, other, matched, cached));
        aliasIds.put("缓存词", group.id());
        when(model.vectors(List.of("首个搜索词", "同义搜索词", "另一主题", "已知主题"), info)).thenReturn(Map.of(
                "首个搜索词", List.of(0f, 1f), "同义搜索词", List.of(0f, 1f),
                "另一主题", List.of(0f, -1f), "已知主题", List.of(1f, 0f)));
        when(model.group(List.of("首个搜索词", "同义搜索词", "另一主题"))).thenReturn(List.of(
                new HotSearchModel.Grouping("新组名", List.of("首个搜索词", "同义搜索词")),
                new HotSearchModel.Grouping("其他组名", List.of("另一主题"))));
        when(model.vectors(List.of("新组名", "其他组名"), info)).thenReturn(Map.of(
                "新组名", List.of(1f, 0f), "其他组名", List.of(0f, -1f)));
        HotSearchGroup created = created("新组名", List.of(1f, 0f));
        HotSearchGroup otherGroup = created("其他组名", List.of(0f, -1f));
        accept(created);
        accept(otherGroup);

        service(model).refresh();

        assertTrue(pending.isEmpty());
        verify(store).contribute(created, List.of(first), now);
        verify(store).contribute(created, List.of(second), now);
        verify(store).contribute(otherGroup, List.of(other), now);
        verify(store).contribute(group, List.of(matched), now);
        verify(store).contribute(group, List.of(cached), now);
        verify(store).remember("首个搜索词", created);
        verify(store).remember("同义搜索词", created);
        verify(model).group(anyList());
        verify(model, times(2)).vectors(anyList(), eq(info));
        verify(model).vectors(List.of("新组名", "其他组名"), info);
    }

    @Test
    void newGroupBudgetAppliesToGroupsAndKeepsAllMembersOfDeferredGroups() {
        properties.setMaxNewGroupsPerRound(1);
        HotSearchStore.Event first = event("首个搜索词", 1);
        HotSearchStore.Event second = event("同义搜索词", 2);
        HotSearchStore.Event deferred = event("另一主题", 3);
        pending.addAll(List.of(first, second, deferred));
        when(model.vectors(anyList(), eq(info))).thenReturn(Map.of("首个搜索词", List.of(0f, 1f),
                "同义搜索词", List.of(0f, 1f), "另一主题", List.of(0f, -1f)));
        when(model.vectors(List.of("新组名"), info)).thenReturn(Map.of("新组名", List.of(0f, 1f)));
        when(model.group(anyList())).thenReturn(List.of(
                new HotSearchModel.Grouping("新组名", List.of("首个搜索词", "同义搜索词")),
                new HotSearchModel.Grouping("其他组名", List.of("另一主题"))));
        HotSearchGroup created = created("新组名", List.of(0f, 1f));
        accept(created);

        service(model).refresh();

        assertEquals(List.of(deferred), pending);
        verify(store).contribute(created, List.of(first), now);
        verify(store).contribute(created, List.of(second), now);
        verify(model).group(List.of("首个搜索词", "同义搜索词", "另一主题"));
        verify(model).vectors(List.of("新组名"), info);
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(deferred)));
    }

    @Test
    void createsNewGroupsWhenFiveHundredGroupsAreAlreadyActive() {
        List<HotSearchGroup> active = new ArrayList<>();
        active.add(group);
        for (int i = 1; i < 500; i++) {
            var existing = new HotSearchGroup("existing-" + i, "已有组" + i, List.of(1f, 0f), "v1");
            active.add(existing);
            rankings.put(existing.id(), new HotSearchStore.Ranking(existing, 1, 1));
        }
        when(store.groups(now)).thenReturn(active);
        HotSearchStore.Event matched = event("已知主题", 1);
        HotSearchStore.Event other = event("另一主题", 2);
        pending.addAll(List.of(matched, other));
        when(model.vectors(anyList(), eq(info))).thenReturn(Map.of("已知主题", List.of(1f, 0f), "另一主题", List.of(0f, 1f)));
        when(model.group(List.of("另一主题"))).thenReturn(List.of(new HotSearchModel.Grouping("新组名", List.of("另一主题"))));
        when(model.vectors(List.of("新组名"), info)).thenReturn(Map.of("新组名", List.of(0f, 1f)));
        HotSearchGroup created = created("新组名", List.of(0f, 1f));
        accept(created);

        service(model).refresh();

        assertTrue(pending.isEmpty());
        verify(store).contribute(group, List.of(matched), now);
        verify(store).contribute(created, List.of(other), now);
        verify(model).group(List.of("另一主题"));
    }

    @Test
    void invalidGeneratedNameRetainsAllEventsForRetry() {
        HotSearchStore.Event first = event("同一个词", 1);
        HotSearchStore.Event second = event("同一个词", 2);
        pending.addAll(List.of(first, second));
        when(model.vectors(List.of("同一个词"), info)).thenReturn(Map.of("同一个词", List.of(0f, 1f)));
        when(model.group(List.of("同一个词"))).thenReturn(List.of(new HotSearchModel.Grouping("!!!", List.of("同一个词"))));

        service(model).refresh();

        assertEquals(List.of(first, second), pending);
        verify(model).group(List.of("同一个词"));
        verify(model, times(1)).vectors(anyList(), any());
        verify(store, never()).remove(anyCollection());
        verify(store, never()).contribute(any(), anyList(), anyLong());
        verify(store, never()).remember(anyString(), any());
    }

    @Test
    void partialNameVectorsOnlyDeferTheirOwnGroups() {
        HotSearchStore.Event first = event("首个搜索词", 1);
        HotSearchStore.Event second = event("另一主题", 2);
        pending.addAll(List.of(first, second));
        when(model.vectors(List.of("首个搜索词", "另一主题"), info)).thenReturn(Map.of(
                "首个搜索词", List.of(0f, 1f), "另一主题", List.of(0f, -1f)));
        when(model.group(anyList())).thenReturn(List.of(
                new HotSearchModel.Grouping("新组名", List.of("首个搜索词")),
                new HotSearchModel.Grouping("其他组名", List.of("另一主题"))));
        when(model.vectors(List.of("新组名", "其他组名"), info)).thenReturn(Map.of("新组名", List.of(0f, 1f)));
        HotSearchGroup created = created("新组名", List.of(0f, 1f));
        accept(created);

        service(model).refresh();

        assertEquals(List.of(second), pending);
        verify(store).contribute(created, List.of(first), now);
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(second)));
    }

    @Test
    void failedNameEmbeddingBatchStillReusesOriginalVectorsAndExistingNames() {
        HotSearchStore.Event first = event("原词组名", 1);
        HotSearchStore.Event second = event("另一主题", 2);
        HotSearchStore.Event third = event("已有组的另一说法", 3);
        pending.addAll(List.of(first, second, third));
        when(model.vectors(List.of("原词组名", "另一主题", "已有组的另一说法"), info)).thenReturn(Map.of(
                "原词组名", List.of(0f, 1f), "另一主题", List.of(0f, -1f), "已有组的另一说法", List.of(0f, 1f)));
        when(model.group(anyList())).thenReturn(List.of(
                new HotSearchModel.Grouping("原词组名", List.of("原词组名")),
                new HotSearchModel.Grouping("其他组名", List.of("另一主题")),
                new HotSearchModel.Grouping("已有组", List.of("已有组的另一说法"))));
        when(model.vectors(List.of("其他组名"), info)).thenThrow(new IllegalStateException("unavailable"));
        HotSearchGroup created = created("原词组名", List.of(0f, 1f));
        accept(created);

        service(model).refresh();

        assertEquals(List.of(second), pending);
        verify(store).contribute(created, List.of(first), now);
        verify(store).contribute(group, List.of(third), now);
        verify(model).vectors(List.of("其他组名"), info);
    }

    @Test
    void repeatedNamesShareOneGroupAndDoNotConsumeExtraCapacity() {
        properties.setMaxNewGroupsPerRound(1);
        HotSearchStore.Event first = event("首个搜索词", 1);
        HotSearchStore.Event second = event("同义搜索词", 2);
        pending.addAll(List.of(first, second));
        when(model.vectors(List.of("首个搜索词", "同义搜索词"), info)).thenReturn(Map.of(
                "首个搜索词", List.of(0f, 1f), "同义搜索词", List.of(0f, 1f)));
        when(model.group(anyList())).thenReturn(List.of(
                new HotSearchModel.Grouping("新组名", List.of("首个搜索词")),
                new HotSearchModel.Grouping("新组名", List.of("同义搜索词"))));
        when(model.vectors(List.of("新组名"), info)).thenReturn(Map.of("新组名", List.of(0f, 1f)));
        HotSearchGroup created = created("新组名", List.of(0f, 1f));
        accept(created);

        service(model).refresh();

        assertTrue(pending.isEmpty());
        verify(store).contribute(created, List.of(first), now);
        verify(store).contribute(created, List.of(second), now);
        verify(model).vectors(List.of("新组名"), info);
    }

    @Test
    void partialLlmResultsContributeValidGroupsAndLeaveOnlyUnassignedEventsPending() {
        HotSearchStore.Event first = event("首个搜索词", 1);
        HotSearchStore.Event second = event("未分配搜索词", 2);
        HotSearchStore.Event third = event("另一个搜索词", 3);
        pending.addAll(List.of(first, second, third));
        QueryEmbeddingPort queries = mock(QueryEmbeddingPort.class);
        LlmPort llm = mock(LlmPort.class);
        when(queries.info(any())).thenReturn(info);
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(List.of(0f, 1f), List.of(0f, 1f), List.of(0f, -1f)), 2, "v1"));
        var response = new LlmPort.ChatResponse("""
                {"groups":[{"name":"首个搜索词","members":[0,0]},
                           {"name":"坏组","members":[1,"bad"]},
                           {"name":"另一个搜索词","members":[0,2]}]}
                """);
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);
        HotSearchGroup firstGroup = created("首个搜索词", List.of(0f, 1f));
        accept(firstGroup);
        HotSearchGroup thirdGroup = created("另一个搜索词", List.of(0f, -1f));
        accept(thirdGroup);

        service(new HotSearchModel(llm, queries, properties, new PromptTemplates())).refresh();

        assertEquals(List.of(second), pending);
        verify(store).contribute(firstGroup, List.of(first), now);
        verify(store).contribute(thirdGroup, List.of(third), now);
        verify(store, never()).remove(argThat((Collection<HotSearchStore.Event> batch) -> batch.contains(second)));
        verify(store).remember("首个搜索词", firstGroup);
        verify(store).remember("另一个搜索词", thirdGroup);
        verify(llm).chat(any(), any());
        verify(llm).embeddings(any(), any());
    }

    private HotSearchGroup created(String name, List<Float> vector) {
        return new HotSearchGroup(HotSearchStore.digest("v1\n" + name), name, vector, "v1");
    }

    private void accept(HotSearchGroup created) {
        when(store.contribute(eq(created), anyList(), eq(now))).thenReturn(true);
        rankings.put(created.id(), new HotSearchStore.Ranking(created, 1, 1));
    }

    private HotSearchService service(HotSearchModel selected) {
        return new HotSearchService(store, history, selected, new HotSearchFilter(properties), properties, guard,
                mock(ThreadPoolTaskExecutor.class), Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), metrics);
    }

    private HotSearchStore.Event event(String text, long userId) {
        return new HotSearchStore.Event("event-" + userId, text, text, userId, now);
    }
}
