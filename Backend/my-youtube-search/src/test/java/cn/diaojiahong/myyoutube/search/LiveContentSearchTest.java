package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.*;
import cn.diaojiahong.myyoutube.search.port.ContentDocPortImpl;
import cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
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
        client=ElasticsearchClients.createImperative(ClientConfiguration.builder().connectedTo("127.0.0.1:19200").build());
        assertEquals("content-it",client.info().clusterName());
        operations=new ElasticsearchTemplate(client);
        var factory=new ElasticsearchRepositoryFactory(operations);
        var index=operations.indexOps(ContentDoc.class); if(index.exists()) index.delete(); index.createWithMapping();
        repository=mock(ContentDocRepository.class,org.mockito.AdditionalAnswers.delegatesTo(factory.getRepository(ContentDocRepository.class)));
        contents=mock(ContentInternalPort.class); users=mock(UserPort.class);
        var user=new UserSummary(); user.setId(9L); user.setNickname(""); when(users.getUser(9L)).thenReturn(user);
        writer=new ContentDocumentWriter(repository,users,mock(ContentVectorService.class));
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
