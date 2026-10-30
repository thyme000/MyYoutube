package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import co.elastic.clients.elasticsearch._types.FieldValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class RecallRefillTest {
    final RecallProperties properties = new RecallProperties();

    final RecallStrategy keyword = mock(RecallStrategy.class);

    final RecallStrategy vector = mock(RecallStrategy.class);

    final RrfFuser fusion = spy(new RrfFuser(new VectorProperties()));

    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final HybridRecallService hybrid;

    RecallRefillTest() throws Exception {
        when(keyword.shouldRun(any())).thenReturn(true);
        when(vector.shouldRun(any())).thenReturn(true);
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of())));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of())));
        hybrid = new HybridRecallService(List.of(keyword, vector), fusion,
                properties, metrics);
    }


    RecallBatch more(String... ids) {
        return new RecallBatch(List.of(ids), List.of(FieldValue.of(ids[ids.length - 1])));
    }

    Function<List<String>, List<String>> filter(Set<String> visible, List<List<String>> calls) {
        return ids -> {
            calls.add(List.copyOf(ids));
            return ids.stream().filter(visible::contains).toList();
        };
    }

    @Test
    void genuinelySmallExhaustedResultsDoNotIssueAnotherQuery() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of(
                "1",
                "2"))));
        List<List<String>> calls = new ArrayList<>();
        assertEquals(
                List.of("2"),
                hybrid.recall(HybridRecallServiceTest.context(), 500, filter(Set.of("2"), calls)).ids());
        verify(keyword, never()).nextBatch(any(), any(), anyInt());
        assertEquals(List.of(List.of("1", "2")), calls);
    }

    @Test
    void uncheckedKeywordCandidatesStillRequireContinuationAfterTheCheckLimit() throws Exception {
        properties.setRefillMaxCandidates(2);
        properties.setRefillBatchSize(2);
        when(keyword.supportsPaging()).thenReturn(true);
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of(
                "1",
                "2",
                "3"))));

        List<List<String>> calls = new ArrayList<>();
        HybridRecallService.FirstPage page = hybrid.recall(
                HybridRecallServiceTest.context(),
                2,
                filter(Set.of("1", "2", "3"), calls));
        assertEquals(List.of("1", "2"), page.ids());
        assertEquals(List.of(List.of("1", "2")), calls);
        assertTrue(page.hasMore());
    }

    @Test
    void readableInitialPoolDoesNotRefillEvenWhenMoreEsCandidatesExist() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        assertEquals(List.of("1", "2"), hybrid.recall(HybridRecallServiceTest.context(), 500, ids -> ids).ids());
        verify(keyword, never()).nextBatch(any(), any(), anyInt());
    }

    @Test
    void refillHydratesOnlyNewIdsAndReusesTheOriginalVectorRoute() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2", "3")));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of(
                "3",
                "4"))));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("3", "5", "6")));
        List<List<String>> calls = new ArrayList<>();
        assertEquals(
                List.of("3", "4", "5", "6"),
                hybrid.recall(HybridRecallServiceTest.context(), 500, filter(Set.of("3", "4", "5", "6"), calls)).ids());
        assertEquals(List.of(List.of("1", "2", "3", "4"), List.of("5", "6")), calls);
        verify(vector, times(1)).firstBatch(any());
        verify(vector, never()).nextBatch(any(), any(), anyInt());
    }

    @Test
    void aDuplicateFromAnotherRouteStillContributesItsKeywordRank() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of(
                "5",
                "4"))));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("4", "6")));
        List<List<String>> calls = new ArrayList<>();
        assertEquals(
                List.of("4", "5", "6"),
                hybrid.recall(HybridRecallServiceTest.context(), 500, filter(Set.of("4", "5", "6"), calls)).ids());
        assertEquals(List.of("6"), calls.get(1));
    }

    @Test
    void roundBudgetStopsAStreamOfFilteredCandidates() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(more("3"), more("4"), more("5"));
        List<List<String>> calls = new ArrayList<>();
        assertTrue(hybrid.recall(HybridRecallServiceTest.context(), 500, filter(Set.of(), calls)).ids().isEmpty());
        verify(keyword, times(2)).nextBatch(any(), any(), anyInt());
        assertEquals(3, calls.size());
    }

    @Test
    void stopsAsSoonAsRefillRestoresTheTargetEvenIfMoreCandidatesExist() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(more("3", "4"));

        assertEquals(List.of("3", "4"), hybrid.recall(
                HybridRecallServiceTest.context(),
                500,
                filter(Set.of("3", "4"), new ArrayList<>()))
                .ids());
        verify(keyword, times(1)).nextBatch(any(), any(), anyInt());
    }

    @Test
    void candidateBudgetAlsoBoundsTheLastRefillBatch() throws Exception {
        properties.setRefillMaxCandidates(3);
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), eq(1))).thenReturn(more("3"));
        assertEquals(List.of("2", "3"), hybrid.recall(
                HybridRecallServiceTest.context(),
                500,
                filter(Set.of("2", "3"), new ArrayList<>()))
                .ids());
        verify(keyword).nextBatch(any(), any(), eq(1));
    }

    @Test
    void refillFailureKeepsAlreadyVerifiedCandidates() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenThrow(new DependencyException(
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                "ES unavailable",
                null));
        assertEquals(
                List.of("1"),
                hybrid.recall(HybridRecallServiceTest.context(), 500, filter(Set.of("1"), new ArrayList<>())).ids());
    }

    @Test
    void hydrationFailureDuringRefillKeepsTheInitialVerifiedCandidates() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("3")));
        assertEquals(List.of("1"), hybrid.recall(HybridRecallServiceTest.context(), 500, ids -> {
            if (ids.contains("3")) throw new DependencyException(
                    ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "video unavailable",
                    null);
            return List.of("1");
        }).ids());
    }

    @Test
    void initialHydrationFailureIsNotReportedAsNoResults() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        assertThrows(DependencyException.class, () -> hybrid.recall(HybridRecallServiceTest.context(), 500, ids -> {
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "video unavailable", null);
        }).ids());
    }

    @Test
    void mixedFusionRunsOnceAfterRefill() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("9", "8")));
        when(vector.firstBatch(any()))
                .thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of("7"))));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("6", "5")));
        SearchContext first = HybridRecallServiceTest.context();
        List<String> initial = hybrid.recall(first, 500, filter(Set.of("9", "7", "6", "5"), new ArrayList<>())).ids();
        assertEquals(List.of("9", "7", "6"), initial);
        verify(fusion, times(1)).fuse(anyList());
    }

}
