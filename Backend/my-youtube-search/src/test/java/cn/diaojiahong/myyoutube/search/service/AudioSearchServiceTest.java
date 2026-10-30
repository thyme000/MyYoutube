package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import cn.diaojiahong.myyoutube.video.api.AudioDocumentPort;
import cn.diaojiahong.myyoutube.search.domain.doc.AudioDoc;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import co.elastic.clients.elasticsearch.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.*;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.apache.http.ProtocolVersion;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.function.Function;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioSearchServiceTest {
    final ElasticsearchClient es = mock(ElasticsearchClient.class);

    final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);

    final IndexOperations index = mock(IndexOperations.class);

    final AudioDocumentPort source = mock(AudioDocumentPort.class);

    final AudioSearchService service = new AudioSearchService(es, source, operations);

    Document doc(long version, boolean deleted) {
        return new Document(1L, 9L, "音频", "ready", "approved", "private", deleted, version, 1000);
    }

    @BeforeEach
    void setup() throws Exception {
        when(operations.indexOps(AudioDoc.class)).thenReturn(index);
        when(index.exists()).thenReturn(true);
    }

    @Test
    void publicAndOwnerQueriesUseDifferentScopesAndAlwaysExcludeTombstones() {
        String publicQuery = AudioSearchService.query(new Query("名字", null, 1, 10)).toString();
        for (String expected : List.of("deleted", "visibility", "public", "processingStatus", "ready", "reviewStatus", "approved", "title", "名字"))
            assertTrue(publicQuery.contains(expected), publicQuery);
        String mine = AudioSearchService.query(new Query("名字", 9L, 1, 10)).toString();
        assertTrue(mine.contains("uploaderId"));
        assertTrue(mine.contains("deleted"));
        assertFalse(mine.contains("visibility"));
        assertThrows(BizException.class, () -> new Query("x", null, 101, 100).validate());
    }

    @Test
    void indexRebuildReadsCurrentRowAndWritesVersionedTombstonesWithoutSignedUrls() throws Exception {
        when(source.document(1L)).thenReturn(doc(7, true));
        doAnswer(i -> {
            Function<IndexRequest.Builder<AudioDoc>, ObjectBuilder<IndexRequest<AudioDoc>>> build = i.getArgument(0);
            IndexRequest<AudioDoc> request = build.apply(new IndexRequest.Builder<>()).build();
            assertEquals(VersionType.ExternalGte, request.versionType());
            assertEquals(7, request.version());
            assertTrue(request.document().isDeleted());
            assertEquals("private", request.document().getVisibility());
            assertFalse(request.document().toString().contains("http"));
            return IndexResponse.of(b -> b.index("audio").id("1").version(7).result(Result.Updated)
                    .shards(s -> s.total(1).successful(1).failed(0)).seqNo(1L).primaryTerm(1L));
        }).when(es).index(any(Function.class));
        service.rebuild(1L);
        verify(source).acknowledge(new Ack(1L, 7));
    }

    @Test
    void newerIndexVersionCanRejectStaleWriteWithoutLosingDatabaseRetry() throws Exception {
        when(source.document(1L)).thenReturn(doc(3, false));
        when(es.index(any(Function.class)))
                .thenThrow(new ElasticsearchException("index", ErrorResponse.of(b -> b.status(409)
                .error(e -> e.type("version_conflict_engine_exception").reason("newer version exists")))));
        service.rebuild(1L);
        verify(source).acknowledge(new Ack(1L, 3));
    }

    @Test
    void failedIndexWriteIsNeverAcknowledged() throws Exception {
        when(source.document(1L)).thenReturn(doc(4, false));
        when(es.index(any(Function.class))).thenThrow(new IOException("unavailable"));
        assertThrows(IllegalStateException.class, () -> service.rebuild(1L));
        verify(source, never()).acknowledge(any());
    }

    @ParameterizedTest @ValueSource(ints = {409, 429, 500})
    void lowLevelTransportOnlyAcknowledgesVersionConflicts(int status) throws Exception {
        Response response = mock(Response.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(
                new ProtocolVersion("HTTP", 1, 1),
                status,
                "failure"));
        ResponseException failure = mock(ResponseException.class);
        when(failure.getResponse()).thenReturn(response);
        when(source.document(1L)).thenReturn(doc(3, false));
        when(es.index(any(Function.class))).thenThrow(failure);
        if (status == 409) {
            assertDoesNotThrow(() -> service.rebuild(1L));
            verify(source).acknowledge(new Ack(1L, 3));
        } else {
            assertThrows(IllegalStateException.class, () -> service.rebuild(1L));
            verify(source, never()).acknowledge(any());
        }
    }

    @Test
    void extraCandidateDeterminesHasMoreWithoutAnAdditionalSearch() throws Exception {
        SearchResponse<AudioDoc> response = SearchResponse.of(b -> b.took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of(Hit.of(v -> v.index("audio").id("1").source(AudioDoc.from(doc(1, false)))),
                        Hit.of(v -> v.index("audio").id("2").source(AudioDoc.from(new Document(
                                2L,
                                9L,
                                "x",
                                "ready",
                                "approved",
                                "public",
                                false,
                                1,
                                999))))))));
        when(es.search(any(Function.class), eq(AudioDoc.class))).thenReturn(response);
        AudioDiscoveryContracts.Slice result = service.search(new Query(null, null, 1, 1));
        assertEquals(List.of(1L), result.ids());
        assertTrue(result.hasMore());
        verify(es, times(1)).search(any(Function.class), eq(AudioDoc.class));
    }

    @Test
    void firstUseCreatesIndexFromAnnotatedModel() throws Exception {
        when(index.exists()).thenReturn(false);
        when(index.createWithMapping()).thenReturn(true);
        when(source.document(1L)).thenReturn(doc(1, false));
        service.rebuild(1L);
        verify(index).createWithMapping();
        verify(source).acknowledge(new Ack(1L, 1));
    }

    @Test
    void racingIndexCreationAcceptsTheOtherCreatorsIndex() {
        when(index.exists()).thenReturn(false, true);
        when(index.createWithMapping()).thenThrow(new DataIntegrityViolationException("index already exists"));
        when(source.document(1L)).thenReturn(doc(1, false));
        assertDoesNotThrow(() -> service.rebuild(1L));
        verify(source).acknowledge(new Ack(1L, 1));
    }

    @Test
    void indexInitializationFailureDoesNotAcknowledgeOrPreventRetry() {
        when(index.exists()).thenReturn(false);
        when(index.createWithMapping()).thenThrow(new DataAccessResourceFailureException("unavailable"));
        when(source.document(1L)).thenReturn(doc(1, false));
        assertThrows(DataAccessResourceFailureException.class, () -> service.rebuild(1L));
        verifyNoInteractions(es);
        verify(source, never()).acknowledge(any());
        when(index.exists()).thenReturn(true);
        assertDoesNotThrow(() -> service.rebuild(1L));
        verify(source).acknowledge(new Ack(1L, 1));
    }

    @Test
    void unconfirmedIndexCreationCannotFallThroughToDynamicMapping() {
        when(index.exists()).thenReturn(false);
        when(index.createWithMapping()).thenReturn(false);
        when(source.document(1L)).thenReturn(doc(1, false));
        assertThrows(IllegalStateException.class, () -> service.rebuild(1L));
        verifyNoInteractions(es);
        verify(source, never()).acknowledge(any());
    }
}
