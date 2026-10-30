package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import cn.diaojiahong.myyoutube.search.port.ContentDocPortImpl;
import cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorPublisher;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentPublicationSyncTest {
    @Test
    void rebuildCreatesFullDocumentAndUsesCurrentVideoState() {
        ContentDocRepository repository = mock(ContentDocRepository.class);
        ContentInternalPort videos = mock(ContentInternalPort.class);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.update(any(UpdateQuery.class), any()))
                .thenReturn(UpdateResponse.of(UpdateResponse.Result.UPDATED));
        var converter = new MappingElasticsearchConverter(new SimpleElasticsearchMappingContext());
        converter.afterPropertiesSet();
        when(operations.getElasticsearchConverter()).thenReturn(converter);
        ContentVectorPublisher vectors = mock(ContentVectorPublisher.class);
        var writer = new ContentDocumentWriter(operations, mock(UserPort.class), vectors);
        var port = new ContentDocPortImpl(repository, videos, writer, operations);
        ContentDocMessage message = ContentDocMessage.builder()
                .contentId(1L)
                .indexVersion(1L)
                .type("video")
                .title("新视频")
                .authorId(9L)
                .description("简介").summary("内容摘要").statusCode("published").visibilityCode("public").tags("技术").build();
        when(videos.searchDocument(1L)).thenReturn(message);
        port.rebuildContent(1L);
        message.setStatusCode("banned");
        message.setIndexVersion(2L);
        port.rebuildContent(1L);
        ArgumentCaptor<UpdateQuery> captured = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations, times(2)).update(captured.capture(), eq(IndexCoordinates.of("content")));
        Map<?,?> first = (Map<?, ?>) captured.getAllValues().get(0).getParams().get("document");
        Map<?,?> second = (Map<?, ?>) captured.getAllValues().get(1).getParams().get("document");
        assertEquals("published", first.get("status"));
        assertEquals("新视频", first.get("title"));
        assertEquals("内容摘要", first.get("summary"));
        assertEquals("banned", second.get("status"));
        for (UpdateQuery update : captured.getAllValues()) {
            assertTrue(update.getScriptedUpsert());
            assertEquals(RefreshPolicy.IMMEDIATE, update.getRefreshPolicy());
            Map<?,?> document = (Map<?, ?>) update.getParams().get("document");
            assertFalse(document.containsKey("vector"));
            assertFalse(document.containsKey("vectorVersion"));
            assertNotNull(update.getScript());
        }
        InOrder ordered = inOrder(operations, vectors);
        ordered.verify(operations).update(any(UpdateQuery.class), eq(IndexCoordinates.of("content")));
        ordered.verify(vectors).publish(1L);
        verifyNoMoreInteractions(vectors);
        when(videos.searchDocument(1L)).thenReturn(null);
        port.rebuildContent(1L);
        verify(repository, never()).deleteById(any());
        verify(operations, times(2)).update(any(UpdateQuery.class), any());
    }

    @Test
    void vectorDeliveryFailurePropagatesAfterDocumentWriteAndCanBeRetried() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.update(any(UpdateQuery.class), any()))
                .thenReturn(UpdateResponse.of(UpdateResponse.Result.UPDATED));
        var converter = new MappingElasticsearchConverter(new SimpleElasticsearchMappingContext());
        converter.afterPropertiesSet();
        when(operations.getElasticsearchConverter()).thenReturn(converter);
        ContentVectorPublisher publisher = mock(ContentVectorPublisher.class);
        doThrow(new IllegalStateException("Broker unavailable")).doNothing().when(publisher).publish(1L);
        var writer = new ContentDocumentWriter(operations, mock(UserPort.class), publisher);
        ContentDocMessage document = ContentDocMessage.builder().contentId(1L).indexVersion(1L).title("标题").authorId(9L)
                .statusCode("published").visibilityCode("public").build();

        assertThrows(IllegalStateException.class, () -> writer.write(document));
        InOrder ordered = inOrder(operations, publisher);
        ordered.verify(operations).update(any(UpdateQuery.class), eq(IndexCoordinates.of("content")));
        ordered.verify(publisher).publish(1L);
        assertDoesNotThrow(() -> writer.write(document));
        verify(operations, times(2)).update(any(UpdateQuery.class), eq(IndexCoordinates.of("content")));
    }

    @Test
    void countSyncUpdatesOnlyCountersAndDoesNotOverwriteVisibilityFromAnOldDocument() {
        ContentDocRepository repository = mock(ContentDocRepository.class);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        var port = new ContentDocPortImpl(
                repository,
                mock(ContentInternalPort.class),
                mock(ContentDocumentWriter.class),
                operations);
        var old = new ContentDoc();
        old.setId(1L);
        old.setVisibility("public");
        old.setStatus("published");
        when(repository.findAllById(List.of(1L,2L))).thenReturn(List.of(old));
        port.syncPlayLikeBatch(List.of(new cn.diaojiahong.myyoutube.search.api.ContentDocPort.CountSync(1L,5L,3L),
                new cn.diaojiahong.myyoutube.search.api.ContentDocPort.CountSync(2L,0L,0L)));
        ArgumentCaptor<List> updates = ArgumentCaptor.forClass(List.class);
        verify(operations).bulkUpdate(updates.capture(),eq(IndexCoordinates.of("content")));
        assertEquals(1,updates.getValue().size());
        UpdateQuery query = (UpdateQuery)updates.getValue().get(0);
        assertEquals("1",query.getId());
        assertEquals(Set.of("playCount","likeCount"),query.getDocument().keySet());
        assertEquals(5L,query.getDocument().get("playCount"));
        verify(repository,never()).saveAll(any());
    }

    @Test
    void authorSyncAllowsEmptyDescriptionAndDoesNotWriteContentStatusOrVisibility() {
        UserPort users = mock(UserPort.class);
        UserDocRepository userDocs = mock(cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository.class);
        ContentDocRepository contents = mock(ContentDocRepository.class);
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        var listener = new cn.diaojiahong.myyoutube.mq.listener.EsDocumentListener(users,userDocs,contents,operations);
        var author = new cn.diaojiahong.myyoutube.user.api.dto.UserSummary();
        author.setId(7L);
        author.setNickname("新昵称");
        author.setFans(8L);
        author.setLikeCount(12L);
        when(users.getUser(7L)).thenReturn(author);
        var publicDoc = new ContentDoc();
        publicDoc.setId(1L);
        publicDoc.setTitle("标题");
        publicDoc.setStatus("published");
        publicDoc.setVisibility("public");
        var privateDoc = new ContentDoc();
        privateDoc.setId(2L);
        privateDoc.setVisibility("private");
        when(contents.findContentDocByAuthorId(7L)).thenReturn(List.of(publicDoc,privateDoc));
        listener.onMessage(new cn.diaojiahong.myyoutube.common.mq.message.UserDocSyncMessage(7L));
        ArgumentCaptor<List> updates = ArgumentCaptor.forClass(List.class);
        verify(operations).bulkUpdate(updates.capture(),eq(IndexCoordinates.of("content")));
        assertEquals(2,updates.getValue().size());
        for (Object update : updates.getValue()) {
            UpdateQuery query = (UpdateQuery)update;
            assertEquals(Map.of("name", "新昵称"), query.getParams());
            assertNull(query.getDocument());
            assertTrue(query.getScript().contains("ctx._source.visibility"));
        }
        verify(contents,never()).saveAll(any());
        ArgumentCaptor<UserDoc> savedUser = ArgumentCaptor.forClass(UserDoc.class);
        verify(userDocs).save(savedUser.capture());
        assertEquals(8L, savedUser.getValue().getFans());
        assertEquals(12L, savedUser.getValue().getLikeCount());
        assertFalse(new ObjectMapper().valueToTree(savedUser.getValue()).has("contentCount"));
    }

    @Test
    void staleWriteDoesNotPublishAnotherVectorTask() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        var converter = new MappingElasticsearchConverter(new SimpleElasticsearchMappingContext());
        converter.afterPropertiesSet();
        when(operations.getElasticsearchConverter()).thenReturn(converter);
        when(operations.update(any(UpdateQuery.class), any()))
                .thenReturn(UpdateResponse.of(UpdateResponse.Result.NOOP));
        ContentVectorPublisher vectors = mock(ContentVectorPublisher.class);
        var writer = new ContentDocumentWriter(operations, mock(UserPort.class), vectors);
        writer.write(ContentDocMessage.builder().contentId(1L).indexVersion(1L).title("标题").authorId(9L)
                .statusCode("published").visibilityCode("public").build());
        verifyNoInteractions(vectors);
    }

    @Test
    void deletionUsesCurrentDatabaseVersionAndDoesNotNeedAuthorOrMetadata() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.update(any(UpdateQuery.class), any()))
                .thenReturn(UpdateResponse.of(UpdateResponse.Result.UPDATED));
        UserPort users = mock(UserPort.class);
        ContentVectorPublisher vectors = mock(ContentVectorPublisher.class);
        var writer = new ContentDocumentWriter(operations, users, vectors);
        ContentInternalPort contents = mock(ContentInternalPort.class);
        ContentDocRepository repository = mock(ContentDocRepository.class);
        var port = new ContentDocPortImpl(repository, contents, writer, operations);
        when(contents.searchDocument(1L))
                .thenReturn(ContentDocMessage.builder().contentId(1L).indexVersion(4L).deleted(true).build());
        port.deleteDoc(1L);
        ArgumentCaptor<UpdateQuery> captured = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations).update(captured.capture(), any());
        Map<?,?> document = (Map<?, ?>) captured.getValue().getParams().get("document");
        assertEquals(4L, document.get("indexVersion"));
        assertEquals(true, document.get("deleted"));
        verifyNoInteractions(users, vectors, repository);
        assertThrows(
                IllegalArgumentException.class,
                () -> writer.write(ContentDocMessage.builder().contentId(1L).deleted(true).build()));
    }
}
