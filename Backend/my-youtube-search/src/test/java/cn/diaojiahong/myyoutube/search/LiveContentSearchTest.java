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
        var vectors = new ContentVectorService(null, client, null, new VectorProperties(), null);
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
