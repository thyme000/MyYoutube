package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
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
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.*;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test void conditionalVectorWriteCannotOverwriteAConcurrentWinner() throws Exception {
        writer.write(message(1));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        var computed = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); computed.set(0, 1f);
        var winner = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); winner.set(1, 1f);
        when(llm.embeddings(any())).thenAnswer(call -> {
            client.update(update -> update.index("content").id("1").doc(Map.of("vector", winner, "vectorVersion", "target:4096")), Void.class);
            return new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(List.of(computed), 4096, "target:4096");
        });
        var builder = new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder(llm, client,
                mock(ContentVectorPublisher.class), mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.class));
        builder.single(1L, "target:4096", mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.Lease.class));
        var actual = client.get(get -> get.index("content").id("1"), Map.class).source();
        List<Number> vector = (List<Number>) actual.get("vector");
        assertEquals(0f, vector.get(0).floatValue()); assertEquals(1f, vector.get(1).floatValue());
        verify(llm).embeddings(any());
    }
    @Test void bulkWriteKeepsConcurrentWinnerAndSkipsNewlyPrivateItem() throws Exception {
        for (long id : List.of(1L, 2L, 3L)) writer.write(message(id));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        var vector = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); vector.set(0, 1f);
        var winner = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f)); winner.set(1, 1f);
        when(llm.embeddings(any())).thenAnswer(call -> {
            client.update(update -> update.index("content").id("1").doc(Map.of("vector", winner, "vectorVersion", "target:4096")), Void.class);
            client.update(update -> update.index("content").id("2").doc(Map.of("visibility", "private")), Void.class);
            return new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(List.of(vector, vector, vector), 4096, "target:4096");
        });
        var guard = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.class);
        var lease = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.Lease.class);
        when(guard.acquireAll(anyList(), anyLong())).thenReturn(lease); when(lease.acquired()).thenReturn(true);
        var observed = spy(client);
        var builder = new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder(llm, observed, mock(ContentVectorPublisher.class), guard);
        assertEquals(new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder.Counts(1, 1, 1, 0),
                builder.batch(List.of(1L, 2L, 3L), "target:4096"));
        assertEquals(winner, repository.findById(1L).orElseThrow().getVector());
        assertEquals("private", repository.findById(2L).orElseThrow().getVisibility());
        assertNull(repository.findById(2L).orElseThrow().getVector());
        assertEquals(vector, repository.findById(3L).orElseThrow().getVector());
        verify(observed).bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class));
        verify(llm).embeddings(any());
    }

    @Test void bulkItemMappingFailureDoesNotUndoItsSuccessfulNeighbor() throws Exception {
        writer.write(message(1L)); writer.write(message(2L));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        var zero = new java.util.ArrayList<>(java.util.Collections.nCopies(4096, 0f));
        var vector = new java.util.ArrayList<>(zero); vector.set(0, 1f);
        when(llm.embeddings(any())).thenReturn(new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(
                List.of(zero, vector), 4096, "target:4096"));
        var guard = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.class);
        var lease = mock(cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard.Lease.class);
        when(guard.acquireAll(anyList(), anyLong())).thenReturn(lease); when(lease.acquired()).thenReturn(true);
        var publisher = mock(ContentVectorPublisher.class);
        var observed = spy(client);
        var builder = new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder(llm, observed, publisher, guard);
        assertEquals(new cn.diaojiahong.myyoutube.search.vector.ContentVectorBuilder.Counts(1, 0, 0, 1),
                builder.batch(List.of(1L, 2L), "target:4096"));
        assertNull(repository.findById(1L).orElseThrow().getVector());
        assertEquals(vector, repository.findById(2L).orElseThrow().getVector());
        verify(publisher).publishRecovery(eq(1L), eq("target:4096"), contains("status=400"));
        verify(observed).bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class));
        verify(llm).embeddings(any());
    }

    ContentDocMessage message(long id) {
        return ContentDocMessage.builder().contentId(id).type("dynamic").title("图集标题").authorId(9L)
                .statusCode("published").visibilityCode("public").coverUrl("selected-cover").playCount(0L).likeCount(0L).build();
    }
    @Test void optionalEmptyMetadataCanBeIndexedAndRebuiltPrivateThenDeleted() {
        var message=message(1); when(contents.searchDocument(1L)).thenReturn(message);
        port.rebuildContent(1L);
        var saved=repository.findById(1L).orElseThrow(); assertEquals("dynamic",saved.getType()); assertEquals("selected-cover",saved.getCoverUrl());
        message.setVisibilityCode("private"); port.rebuildContent(1L);
        assertEquals("private",repository.findById(1L).orElseThrow().getVisibility());
        when(contents.searchDocument(1L)).thenReturn(null); port.rebuildContent(1L); assertFalse(repository.existsById(1L));
    }
    @Test void staleCounterSnapshotCannotRestorePublicStateOrResurrectDeletedDocument() {
        writer.write(message(1)); var old=repository.findById(1L).orElseThrow();
        var current=message(1); current.setVisibilityCode("private"); current.setStatusCode("banned"); writer.write(current);
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

        first.setVisibilityCode("private"); writer.write(first);
        assertEquals(vector, repository.findById(1L).orElseThrow().getVector());
        assertEquals(List.of("2"), vectors.knnSearch(query, null, 10));
        first.setStatusCode("banned"); first.setVisibilityCode("public"); writer.write(first);
        assertEquals(vector, repository.findById(1L).orElseThrow().getVector());
        assertEquals(List.of("2"), vectors.knnSearch(query, null, 10));
        first.setStatusCode("published"); writer.write(first);
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
