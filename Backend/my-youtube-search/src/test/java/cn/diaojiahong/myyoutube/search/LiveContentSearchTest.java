package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.common.mq.message.UserDocSyncMessage;
import cn.diaojiahong.myyoutube.mq.listener.EsDocumentListener;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.*;
import cn.diaojiahong.myyoutube.search.port.ContentDocPortImpl;
import cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorPublisher;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.*;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="content.integration",matches="true")
class LiveContentSearchTest {
    co.elastic.clients.elasticsearch.ElasticsearchClient client;
    ElasticsearchTemplate operations;
    ContentDocRepository repository;
    ContentInternalPort contents;
    ContentDocPortImpl port;
    UserPort users;
    ContentDocumentWriter writer;

    @BeforeEach void setup() throws Exception {
        client=ElasticsearchClients.createImperative(ClientConfiguration.builder()
                .connectedTo(System.getProperty("content.es.address", "127.0.0.1:19201")).build());
        assertEquals("content-it",client.info().clusterName());
        operations=new ElasticsearchTemplate(client);
        var factory=new ElasticsearchRepositoryFactory(operations);
        var index=operations.indexOps(ContentDoc.class); if(index.exists()) index.delete(); index.createWithMapping();
        repository=mock(ContentDocRepository.class,org.mockito.AdditionalAnswers.delegatesTo(factory.getRepository(ContentDocRepository.class)));
        contents=mock(ContentInternalPort.class); users=mock(UserPort.class);
        var user=new UserSummary(); user.setId(9L); user.setNickname(""); when(users.getUser(9L)).thenReturn(user);
        writer=new ContentDocumentWriter(operations,users,mock(ContentVectorPublisher.class));
        port=new ContentDocPortImpl(repository,contents,writer,operations);
    }
    @AfterEach void close() throws Exception { if(client!=null) client._transport().close(); }

    @ParameterizedTest @CsvSource({"video,private", "video,banned", "video,deleted", "dynamic,private", "dynamic,banned", "dynamic,deleted"})
    void latePublicSnapshotCannotOverwriteNewerRestriction(String type, String restriction) throws Exception {
        var old = message(1); old.setType(type);
        var current = message(1); current.setType(type); current.setIndexVersion(2L);
        if ("deleted".equals(restriction)) current.setDeleted(true);
        else if ("banned".equals(restriction)) current.setStatusCode("banned");
        else current.setVisibilityCode("private");
        var started = new CountDownLatch(1); var resume = new CountDownLatch(1); var calls = new AtomicInteger();
        when(users.getUser(9L)).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS));
            }
            var user = new UserSummary(); user.setId(9L); user.setNickname("作者"); return user;
        });
        var delayed = CompletableFuture.runAsync(() -> writer.write(old));
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            writer.write(current);
        } finally { resume.countDown(); }
        delayed.get(10, TimeUnit.SECONDS);
        var saved = repository.findById(1L).orElseThrow();
        assertEquals(2L, saved.getIndexVersion());
        if ("deleted".equals(restriction)) { assertTrue(saved.getDeleted()); assertNull(saved.getTitle()); }
        else if ("banned".equals(restriction)) assertEquals("banned", saved.getStatus());
        else assertEquals("private", saved.getVisibility());
        var source = client.get(g -> g.index("content").id("1"), Map.class).source();
        assertEquals(List.of(), ((Map<?, ?>) source.get("suggest")).get("input"));
    }

    @ParameterizedTest @ValueSource(strings = {"private", "banned", "deleted"})
    void delayedAuthorSnapshotCannotRestoreRestrictedSuggestions(String restriction) {
        var userIndex = operations.indexOps(UserDoc.class);
        if (userIndex.exists()) userIndex.delete(); userIndex.createWithMapping();
        var userDocs = new ElasticsearchRepositoryFactory(operations).getRepository(UserDocRepository.class);
        writer.write(message(1)); var old = repository.findById(1L).orElseThrow();
        var current = message(1); current.setIndexVersion(2L);
        if ("deleted".equals(restriction)) current.setDeleted(true);
        else if ("banned".equals(restriction)) current.setStatusCode("banned");
        else current.setVisibilityCode("private");
        var delayed = mock(ContentDocRepository.class);
        when(delayed.findContentDocByAuthorId(9L)).thenAnswer(call -> {
            writer.write(current); return List.of(old);
        });
        var listener = new EsDocumentListener(users, userDocs, delayed, operations);
        listener.onMessage(new UserDocSyncMessage(9L));
        operations.indexOps(ContentDoc.class).refresh();
        assertEquals(2L, repository.findById(1L).orElseThrow().getIndexVersion());
        assertEquals(0, repository.findById(1L).orElseThrow().getSuggest().getInput().length);
    }

    @Test void conditionalVectorWriteCannotOverwriteAConcurrentWinner() throws Exception {
        writer.write(message(1));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        var computed = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); computed.set(0, 1f);
        var winner = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); winner.set(1, 1f);
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenAnswer(call -> {
            client.update(update -> update.index("content").id("1").doc(Map.of("vector", winner, "vectorVersion", "target:4096")), Void.class);
            return new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(List.of(computed), 4096, "target:4096");
        });
        var builder = new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder(llm, client,
                mock(ContentVectorPublisher.class), mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.class), new VectorProperties());
        builder.single(1L, "target:4096", mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.Lease.class));
        var actual = client.get(get -> get.index("content").id("1"), Map.class).source();
        List<Number> vector = (List<Number>) actual.get("vector");
        assertEquals(0f, vector.get(0).floatValue()); assertEquals(1f, vector.get(1).floatValue());
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }
    @Test void bulkWriteKeepsConcurrentWinnerAndSkipsNewlyPrivateItem() throws Exception {
        for (long id : List.of(1L, 2L, 3L)) writer.write(message(id));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        var vector = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); vector.set(0, 1f);
        var winner = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); winner.set(1, 1f);
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenAnswer(call -> {
            client.update(update -> update.index("content").id("1").doc(Map.of("vector", winner, "vectorVersion", "target:4096")), Void.class);
            client.update(update -> update.index("content").id("2").doc(Map.of("visibility", "private")), Void.class);
            return new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(List.of(vector, vector, vector), 4096, "target:4096");
        });
        var guard = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.class);
        var lease = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.Lease.class);
        when(guard.acquireAll(anyList(), anyLong())).thenReturn(lease); when(lease.acquired()).thenReturn(true);
        var observed = spy(client);
        var builder = new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder(llm, observed, mock(ContentVectorPublisher.class), guard, new VectorProperties());
        assertEquals(new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder.Counts(1, 1, 1, 0),
                builder.batch(List.of(1L, 2L, 3L), "target:4096"));
        assertEquals(winner, repository.findById(1L).orElseThrow().getVector());
        assertEquals("private", repository.findById(2L).orElseThrow().getVisibility());
        assertNull(repository.findById(2L).orElseThrow().getVector());
        assertEquals(vector, repository.findById(3L).orElseThrow().getVector());
        verify(observed).bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    @Test void bulkItemMappingFailureDoesNotUndoItsSuccessfulNeighbor() throws Exception {
        writer.write(message(1L)); writer.write(message(2L));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        var zero = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f));
        var vector = new java.util.ArrayList<>(zero); vector.set(0, 1f);
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenReturn(new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(
                List.of(zero, vector), 4096, "target:4096"));
        var guard = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.class);
        var lease = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.Lease.class);
        when(guard.acquireAll(anyList(), anyLong())).thenReturn(lease); when(lease.acquired()).thenReturn(true);
        var publisher = mock(ContentVectorPublisher.class);
        var observed = spy(client);
        var builder = new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder(llm, observed, publisher, guard, new VectorProperties());
        assertEquals(new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder.Counts(1, 0, 0, 1),
                builder.batch(List.of(1L, 2L), "target:4096"));
        assertNull(repository.findById(1L).orElseThrow().getVector());
        assertEquals(vector, repository.findById(2L).orElseThrow().getVector());
        verify(publisher).publishRecovery(eq(1L), eq("target:4096"), contains("status=400"));
        verify(observed).bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    ContentDocMessage message(long id) {
        return ContentDocMessage.builder().contentId(id).indexVersion(1L).type("dynamic").title("图集标题").authorId(9L)
                .statusCode("published").visibilityCode("public").coverUrl("selected-cover").playCount(0L).likeCount(0L).build();
    }
    @Test void optionalEmptyMetadataCanBeIndexedAndRebuiltPrivateThenDeleted() {
        var message=message(1); when(contents.searchDocument(1L)).thenReturn(message);
        port.rebuildContent(1L);
        var saved=repository.findById(1L).orElseThrow(); assertEquals("dynamic",saved.getType()); assertEquals("selected-cover",saved.getCoverUrl());
        message.setVisibilityCode("private"); message.setIndexVersion(2L); port.rebuildContent(1L);
        assertEquals("private",repository.findById(1L).orElseThrow().getVisibility());
        when(contents.searchDocument(1L)).thenReturn(ContentDocMessage.builder().contentId(1L).indexVersion(3L).deleted(true).build());
        port.deleteDoc(1L);
        var tombstone = repository.findById(1L).orElseThrow();
        assertEquals(true, tombstone.getDeleted()); assertNull(tombstone.getTitle());
        writer.write(message); assertEquals(true, repository.findById(1L).orElseThrow().getDeleted());
    }
    @Test void staleCounterSnapshotCannotRestorePublicStateOrResurrectDeletedDocument() {
        writer.write(message(1)); var old=repository.findById(1L).orElseThrow();
        var current=message(1); current.setIndexVersion(2L); current.setVisibilityCode("private"); current.setStatusCode("banned"); writer.write(current);
        doReturn(List.of(old)).when(repository).findAllById(List.of(1L));
        var counts=List.of(new cn.diaojiahong.myyoutube.search.api.ContentDocPort.CountSync(1L,5L,3L));
        port.syncPlayLikeBatch(counts);
        var saved=repository.findById(1L).orElseThrow(); assertEquals("private",saved.getVisibility()); assertEquals("banned",saved.getStatus());
        assertEquals(5L,saved.getPlayCount()); assertEquals(3L,saved.getLikeCount());
        repository.deleteById(1L);
        assertThrows(RuntimeException.class,() -> port.syncPlayLikeBatch(counts));
        assertFalse(repository.existsById(1L));
    }
    @Test void metadataUpdatesPreserveVectorsWhileKnnFiltersPrivateAndBannedContent() throws Exception {
        var first = message(1); first.setSummary("内容摘要");
        writer.write(first); writer.write(message(2));
        var vector = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0.0f));
        vector.set(0, 1.0f);
        for (String id : List.of("1", "2")) {
            client.update(update -> update.index("content").id(id)
                    .doc(Map.of("vector", vector, "vectorVersion", "test:4096")), Void.class);
        }
        operations.indexOps(ContentDoc.class).refresh();
        var vectors = new ContentVectorService(null, client, null, new VectorProperties(), null, null, null);
        var query = new ContentVectorService.QueryVector(vector, "test:4096");
        assertEquals(Set.of("1", "2"), Set.copyOf(vectors.knnSearch(query, null, 10)));
        assertEquals("内容摘要", repository.findById(1L).orElseThrow().getSummary());
        assertFalse(client.indices().getMapping(mapping -> mapping.index("content"))
                .result().get("content").mappings().properties().get("summary").text().index());

        first.setVisibilityCode("private"); first.setIndexVersion(2L); writer.write(first);
        assertEquals(vector, repository.findById(1L).orElseThrow().getVector());
        assertEquals(List.of("2"), vectors.knnSearch(query, null, 10));
        first.setStatusCode("banned"); first.setVisibilityCode("public"); first.setIndexVersion(3L); writer.write(first);
        assertEquals(vector, repository.findById(1L).orElseThrow().getVector());
        assertEquals(List.of("2"), vectors.knnSearch(query, null, 10));
        first.setStatusCode("published"); first.setIndexVersion(4L); writer.write(first);
        assertEquals(vector, repository.findById(1L).orElseThrow().getVector());
        assertEquals(Set.of("1", "2"), Set.copyOf(vectors.knnSearch(query, null, 10)));
    }
    @Test void nicknameBulkUpdateKeepsPrivacyAndEmptyNicknameIsLegal() {
        var userIndex=operations.indexOps(cn.diaojiahong.myyoutube.search.domain.doc.UserDoc.class);
        if(userIndex.exists()) userIndex.delete(); userIndex.createWithMapping();
        var userDocs=new ElasticsearchRepositoryFactory(operations).getRepository(UserDocRepository.class);
        var current=message(1); current.setVisibilityCode("private"); writer.write(current);
        var listener=new cn.diaojiahong.myyoutube.mq.listener.EsDocumentListener(users,userDocs,repository,operations);
        listener.onMessage(new cn.diaojiahong.myyoutube.common.mq.message.UserDocSyncMessage(9L));
        assertEquals("private",repository.findById(1L).orElseThrow().getVisibility()); assertTrue(userDocs.existsById(9L));
    }
}
