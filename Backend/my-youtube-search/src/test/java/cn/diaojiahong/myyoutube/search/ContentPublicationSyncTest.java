package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.search.port.ContentDocPortImpl;
import cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorPublisher;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentPublicationSyncTest {
    @Test void rebuildCreatesFullDocumentAndUsesCurrentVideoState() {
        var repository = mock(ContentDocRepository.class);
        var videos = mock(ContentInternalPort.class);
        var operations = mock(ElasticsearchOperations.class);
        var converter = new MappingElasticsearchConverter(new SimpleElasticsearchMappingContext());
        converter.afterPropertiesSet();
        when(operations.getElasticsearchConverter()).thenReturn(converter);
        var vectors = mock(ContentVectorPublisher.class);
        var writer = new ContentDocumentWriter(operations, mock(UserPort.class), vectors);
        var port = new ContentDocPortImpl(repository, videos, writer, operations);
        var message = ContentDocMessage.builder().contentId(1L).type("video").title("新视频").authorId(9L)
                .description("简介").summary("内容摘要").statusCode("published").visibilityCode("public").tags("技术").build();
        when(videos.searchDocument(1L)).thenReturn(message);
        port.rebuildContent(1L);
        message.setStatusCode("banned");
        port.rebuildContent(1L);
        var captured = ArgumentCaptor.forClass(UpdateQuery.class);
        verify(operations, times(2)).update(captured.capture(), eq(IndexCoordinates.of("content")));
        assertEquals("published", captured.getAllValues().get(0).getDocument().get("status"));
        assertEquals("新视频", captured.getAllValues().get(0).getDocument().get("title"));
        assertEquals("内容摘要", captured.getAllValues().get(0).getDocument().get("summary"));
        assertEquals("banned", captured.getAllValues().get(1).getDocument().get("status"));
        for (var update : captured.getAllValues()) {
            assertTrue(update.getDocAsUpsert());
            assertEquals(RefreshPolicy.IMMEDIATE, update.getRefreshPolicy());
            assertFalse(update.getDocument().containsKey("vector"));
            assertFalse(update.getDocument().containsKey("vectorVersion"));
        }
        var ordered = inOrder(operations, vectors);
        ordered.verify(operations).update(any(UpdateQuery.class), eq(IndexCoordinates.of("content")));
        ordered.verify(vectors).publish(1L);
        verifyNoMoreInteractions(vectors);
        when(videos.searchDocument(1L)).thenReturn(null);
        port.rebuildContent(1L);
        verify(repository).deleteById(1L);
    }

    @Test void vectorDeliveryFailurePropagatesAfterDocumentWriteAndCanBeRetried() {
        var operations = mock(ElasticsearchOperations.class);
        var converter = new MappingElasticsearchConverter(new SimpleElasticsearchMappingContext());
        converter.afterPropertiesSet();
        when(operations.getElasticsearchConverter()).thenReturn(converter);
        var publisher = mock(ContentVectorPublisher.class);
        doThrow(new IllegalStateException("Broker unavailable")).doNothing().when(publisher).publish(1L);
        var writer = new ContentDocumentWriter(operations, mock(UserPort.class), publisher);
        var document = ContentDocMessage.builder().contentId(1L).title("标题").authorId(9L)
                .statusCode("published").visibilityCode("public").build();

        assertThrows(IllegalStateException.class, () -> writer.write(document));
        var ordered = inOrder(operations, publisher);
        ordered.verify(operations).update(any(UpdateQuery.class), eq(IndexCoordinates.of("content")));
        ordered.verify(publisher).publish(1L);
        assertDoesNotThrow(() -> writer.write(document));
        verify(operations, times(2)).update(any(UpdateQuery.class), eq(IndexCoordinates.of("content")));
    }

    @Test void countSyncUpdatesOnlyCountersAndDoesNotOverwriteVisibilityFromAnOldDocument() {
        var repository = mock(ContentDocRepository.class);
        var operations = mock(ElasticsearchOperations.class);
        var port = new ContentDocPortImpl(repository,mock(ContentInternalPort.class),mock(ContentDocumentWriter.class),operations);
        var old = new ContentDoc(); old.setId(1L); old.setVisibility("public"); old.setStatus("published");
        when(repository.findAllById(List.of(1L,2L))).thenReturn(List.of(old));
        port.syncPlayLikeBatch(List.of(new cn.diaojiahong.myyoutube.search.api.ContentDocPort.CountSync(1L,5L,3L),
                new cn.diaojiahong.myyoutube.search.api.ContentDocPort.CountSync(2L,0L,0L)));
        var updates = ArgumentCaptor.forClass(List.class);
        verify(operations).bulkUpdate(updates.capture(),eq(IndexCoordinates.of("content")));
        assertEquals(1,updates.getValue().size());
        var query = (UpdateQuery)updates.getValue().get(0);
        assertEquals("1",query.getId()); assertEquals(Set.of("playCount","likeCount"),query.getDocument().keySet());
        assertEquals(5L,query.getDocument().get("playCount"));
        verify(repository,never()).saveAll(any());
    }

    @Test void authorSyncAllowsEmptyDescriptionAndDoesNotWriteContentStatusOrVisibility() {
        var users = mock(UserPort.class);
        var userDocs = mock(cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository.class);
        var contents = mock(ContentDocRepository.class);
        var operations = mock(ElasticsearchOperations.class);
        var listener = new cn.diaojiahong.myyoutube.mq.listener.EsDocumentListener(users,userDocs,contents,operations);
        var author = new cn.diaojiahong.myyoutube.user.api.dto.UserSummary(); author.setId(7L); author.setNickname("新昵称");
        when(users.getUser(7L)).thenReturn(author);
        var publicDoc = new ContentDoc(); publicDoc.setId(1L); publicDoc.setTitle("标题");
        publicDoc.setStatus("published"); publicDoc.setVisibility("public");
        var privateDoc = new ContentDoc(); privateDoc.setId(2L); privateDoc.setVisibility("private");
        when(contents.findContentDocByAuthorId(7L)).thenReturn(List.of(publicDoc,privateDoc));
        listener.onMessage(new cn.diaojiahong.myyoutube.common.mq.message.UserDocSyncMessage(7L));
        var updates = ArgumentCaptor.forClass(List.class);
        verify(operations).bulkUpdate(updates.capture(),eq(IndexCoordinates.of("content")));
        assertEquals(2,updates.getValue().size());
        for (var update : updates.getValue()) {
            var query = (UpdateQuery)update;
            assertEquals(Set.of("authorName","suggest"),query.getDocument().keySet());
            assertEquals("新昵称",query.getDocument().get("authorName"));
        }
        verify(contents,never()).saveAll(any());
        verify(userDocs).save(any());
    }
}
