package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import co.elastic.clients.elasticsearch._types.FieldValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.Comparator;
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
    final LatestFuser latest = mock(LatestFuser.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final RecallPipeline pipeline;

    RecallRefillTest() throws Exception {
        when(keyword.shouldRun(any())).thenReturn(true); when(vector.shouldRun(any())).thenReturn(true);
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of())));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of())));
        when(latest.fuse(any(), anyList())).thenAnswer(call -> {
            List<List<String>> routes = call.getArgument(1);
            return routes.stream().flatMap(List::stream).distinct().sorted(Comparator.reverseOrder()).toList();
        });
        pipeline = new RecallPipeline(List.of(keyword, vector), new RrfFuser(new VectorProperties()), latest,
                properties, metrics);
    }


    RecallBatch more(String... ids) { return new RecallBatch(List.of(ids), List.of(FieldValue.of(ids[ids.length - 1]))); }

    Function<List<String>, List<String>> filter(Set<String> visible, List<List<String>> calls) {
        return ids -> {
            calls.add(List.copyOf(ids));
            return ids.stream().filter(visible::contains).toList();
        };
    }

    @Test void genuinelySmallExhaustedResultsDoNotIssueAnotherQuery() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of("1", "2"))));
        List<List<String>> calls = new ArrayList<>();
        assertEquals(List.of("2"), pipeline.execute(RecallPipelineTest.context(), filter(Set.of("2"), calls)));
        verify(keyword, never()).nextBatch(any(), any(), anyInt());
        assertEquals(List.of(List.of("1", "2")), calls);
    }

    @Test void readableInitialPoolDoesNotRefillEvenWhenMoreEsCandidatesExist() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        assertEquals(List.of("1", "2"), pipeline.execute(RecallPipelineTest.context()));
        verify(keyword, never()).nextBatch(any(), any(), anyInt());
    }

    @Test void refillHydratesOnlyNewIdsAndReusesTheOriginalVectorRoute() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2", "3")));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of("3", "4"))));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("3", "5", "6")));
        List<List<String>> calls = new ArrayList<>();
        assertEquals(List.of("3", "4", "5", "6"), pipeline.execute(RecallPipelineTest.context(), filter(Set.of("3", "4", "5", "6"), calls)));
        assertEquals(List.of(List.of("1", "2", "3", "4"), List.of("5", "6")), calls);
        verify(vector, times(1)).firstBatch(any());
        verify(vector, never()).nextBatch(any(), any(), anyInt());
    }

    @Test void aDuplicateFromAnotherRouteStillContributesItsKeywordRank() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of("5", "4"))));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("4", "6")));
        List<List<String>> calls = new ArrayList<>();
        assertEquals(List.of("4", "5", "6"), pipeline.execute(RecallPipelineTest.context(), filter(Set.of("4", "5", "6"), calls)));
        assertEquals(List.of("6"), calls.get(1));
    }

    @Test void roundBudgetStopsAStreamOfFilteredCandidates() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(more("3"), more("4"), more("5"));
        List<List<String>> calls = new ArrayList<>();
        assertTrue(pipeline.execute(RecallPipelineTest.context(), filter(Set.of(), calls)).isEmpty());
        verify(keyword, times(2)).nextBatch(any(), any(), anyInt());
        assertEquals(3, calls.size());
    }

    @Test void stopsAsSoonAsRefillRestoresTheTargetEvenIfMoreCandidatesExist() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(more("3", "4"));

        assertEquals(List.of("3", "4"), pipeline.execute(RecallPipelineTest.context(),
                filter(Set.of("3", "4"), new ArrayList<>())));
        verify(keyword, times(1)).nextBatch(any(), any(), anyInt());
    }

    @Test void candidateBudgetAlsoBoundsTheLastRefillBatch() throws Exception {
        properties.setRefillMaxCandidates(3);
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), eq(1))).thenReturn(more("3"));
        assertEquals(List.of("2", "3"), pipeline.execute(RecallPipelineTest.context(), filter(Set.of("2", "3"), new ArrayList<>())));
        verify(keyword).nextBatch(any(), any(), eq(1));
    }

    @Test void refillFailureKeepsAlreadyVerifiedCandidates() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenThrow(new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "ES unavailable", null));
        assertEquals(List.of("1"), pipeline.execute(RecallPipelineTest.context(), filter(Set.of("1"), new ArrayList<>())));
    }

    @Test void hydrationFailureDuringRefillKeepsTheInitialVerifiedCandidates() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("3")));
        assertEquals(List.of("1"), pipeline.execute(RecallPipelineTest.context(), ids -> {
            if (ids.contains("3")) throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "video unavailable", null);
            return List.of("1");
        }));
    }

    @Test void initialHydrationFailureIsNotReportedAsNoResults() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("1", "2")));
        assertThrows(DependencyException.class, () -> pipeline.execute(RecallPipelineTest.context(), ids -> {
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "video unavailable", null);
        }));
    }

    @Test void latestRefillDepthIsIndependentOfTheRequestedPageAndFusionRunsOnce() throws Exception {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(more("9", "8")));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of("7"))));
        when(keyword.nextBatch(any(), any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("6", "5")));
        SearchContext first = RecallPipelineTest.context(); first.getQuery().setSortByLatest(true);
        List<String> initial = pipeline.execute(first, filter(Set.of("9", "7", "6", "5"), new ArrayList<>()));
        SearchContext second = RecallPipelineTest.context(); second.getQuery().setSortByLatest(true); second.getQuery().setPageNo(2);
        assertEquals(initial, pipeline.execute(second, filter(Set.of("9", "7", "6", "5"), new ArrayList<>())));
        assertEquals(List.of("9", "7", "6"), initial);
        verify(latest, times(2)).fuse(any(), anyList());
    }

}
