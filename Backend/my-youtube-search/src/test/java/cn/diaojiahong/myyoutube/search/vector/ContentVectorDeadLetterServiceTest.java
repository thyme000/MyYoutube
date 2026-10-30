package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorDeadLetterServiceTest {
    private final ContentVectorDeadLetters reader = mock(ContentVectorDeadLetters.class);

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);

    private final ContentVectorPublisher publisher = mock(ContentVectorPublisher.class);

    private final VectorProperties properties = new VectorProperties();

    private final ContentVectorDeadLetterService service = new ContentVectorDeadLetterService(
            reader,
            client,
            publisher,
            properties);

    private final Reference reference = new Reference("broker", 0, 5L);

    @Test
    void queryCombinesFailureHistoryWithOneBatchOfCurrentStates() throws Exception {
        when(reader.query(null, 20)).thenReturn(new ContentVectorDeadLetters.Page(
                List.of(entry(1L), entry(2L), entry(3L), entry(4L), entry(5L), entry(6L), entry(7L), entry(null), entry(2L)),
                "next"));
        ContentDoc pending = content();
        ContentDoc recovered = content();
        recovered.setVectorVersion("model:1");
        ContentDoc privateDoc = content();
        privateDoc.setVisibility("private");
        ContentDoc banned = content();
        banned.setStatus("banned");
        ContentDoc empty = content();
        empty.setTitle(" ");
        MgetResponse<ContentDoc> response = MgetResponse.of(b -> b.docs(List.of(
                result(1, pending),
                result(2, recovered),
                result(3, privateDoc),
                result(4, banned),
                result(5, null),
                result(6, empty),
                new MultiGetResponseItem.Builder<ContentDoc>().failure(f -> f.index("content").id("7")
                        .error(e -> e.type("unavailable").reason("unavailable"))).build())));
        when(client.mget(any(Function.class), eq(ContentDoc.class))).thenReturn(response);

        Page page = service.query(null, 20);
        assertEquals(List.of(State.UNRECOVERED, State.RECOVERED, State.INELIGIBLE, State.INELIGIBLE, State.INELIGIBLE,
                State.INELIGIBLE, State.UNKNOWN, State.INVALID_MESSAGE, State.RECOVERED), page.items()
                        .stream()
                        .map(Item::state)
                        .toList());
        assertEquals("CONTENT_MISSING", page.items().get(4).reason());
        assertEquals("EMPTY_TEXT", page.items().get(5).reason());
        assertEquals("model:1", page.items().get(1).vectorVersion());
        assertEquals("next", page.nextCursor());
        assertTrue(page.enabled());
        ArgumentCaptor<Function<MgetRequest.Builder,
        ObjectBuilder<MgetRequest>>> request = ArgumentCaptor.forClass(Function.class);
        verify(client).mget(request.capture(), eq(ContentDoc.class));
        MgetRequest captured = request.getValue().apply(new MgetRequest.Builder()).build();
        assertEquals(7, captured.ids().size());
        assertFalse(captured.sourceIncludes().contains("vector"));
        verifyNoInteractions(publisher);
    }

    @Test
    void replayUsesOriginalMessageAndThenRecognizesCompletedWork() throws Exception {
        when(reader.find(reference)).thenReturn(entry(1L));
        ContentDoc completed = content();
        completed.setVectorVersion("model:1");
        when(client.mget(any(Function.class), eq(ContentDoc.class)))
                .thenReturn(response(content()), response(completed));
        assertEquals(ReplayStatus.ACCEPTED, service.replay(reference, 1L).status());
        assertEquals(ReplayStatus.ALREADY_COMPLETED, service.replay(reference, 1L).status());
        verify(publisher, times(1)).publish(1L);
    }

    @Test
    void unreadableStateRemainsUnknownAndCannotBeReplayed() throws Exception {
        when(reader.query(null, 20)).thenReturn(new ContentVectorDeadLetters.Page(List.of(entry(1L)), null));
        when(reader.find(reference)).thenReturn(entry(1L));
        when(client.mget(any(Function.class), eq(ContentDoc.class))).thenThrow(new IOException("ES offline"));
        assertEquals(State.UNKNOWN, service.query(null, 20).items().get(0).state());
        assertEquals(ReplayStatus.STATE_UNAVAILABLE, service.replay(reference, 1L).status());
        verifyNoInteractions(publisher);
    }

    @Test
    void missingAndMalformedDeadLettersCannotTriggerArbitraryContent() {
        assertEquals(ReplayStatus.NOT_FOUND, service.replay(reference, 1L).status());
        when(reader.find(reference)).thenReturn(entry(null));
        assertEquals(ReplayStatus.INVALID_MESSAGE, service.replay(reference, 1L).status());
        verifyNoInteractions(client, publisher);
    }

    @Test
    void disabledOrIneligibleContentDoesNotPublish() throws Exception {
        when(reader.find(reference)).thenReturn(entry(1L));
        ContentDoc privateDoc = content();
        privateDoc.setVisibility("private");
        when(client.mget(any(Function.class), eq(ContentDoc.class))).thenReturn(
                response(privateDoc),
                response(content()));
        assertEquals(ReplayStatus.INELIGIBLE, service.replay(reference, 1L).status());
        properties.setEnabled(false);
        assertEquals(ReplayStatus.DISABLED, service.replay(reference, 1L).status());
        verifyNoInteractions(publisher);
    }

    @Test
    void sendFailureDoesNotClaimAcceptanceOrCompletion() throws Exception {
        when(reader.find(reference)).thenReturn(entry(1L));
        when(client.mget(any(Function.class), eq(ContentDoc.class))).thenReturn(response(content()));
        doThrow(new IllegalStateException("send timeout")).when(publisher).publish(1L);
        assertEquals(ReplayStatus.DELIVERY_UNCONFIRMED, service.replay(reference, 1L).status());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void missingOrInvalidAuditActorCannotReplay(Long actorId) {
        BizException error = assertThrows(BizException.class, () -> service.replay(reference, actorId));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.getErrorCode());
        verifyNoInteractions(reader, client, publisher);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 42})
    void trustedInternalReplayAcceptsTheForwardedAuditActor(long actorId) {
        assertEquals(ReplayStatus.NOT_FOUND, service.replay(reference, actorId).status());
        verify(reader).find(reference);
    }

    @Test
    void emptyPageDoesNotReadEs() {
        when(reader.query(null, 20)).thenReturn(new ContentVectorDeadLetters.Page(List.of(), null));
        assertTrue(service.query(null, 20).items().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void recoveryRequiresRequestedVersionAndReplayPreservesItsIdentity() throws Exception {
        var targeted = new ContentVectorDeadLetters.Entry(reference, "message", 1L, 100L, 3, "model:2", "write failed");
        when(reader.query(null, 20)).thenReturn(new ContentVectorDeadLetters.Page(List.of(targeted), null));
        when(reader.find(reference)).thenReturn(targeted);
        ContentDoc old = content();
        old.setVectorVersion("model:1");
        ContentDoc current = content();
        current.setVectorVersion("model:2");
        when(client.mget(any(Function.class), eq(ContentDoc.class))).thenReturn(
                response(old),
                response(old),
                response(current));
        Item item = service.query(null, 20).items().get(0);
        assertEquals(State.UNRECOVERED, item.state());
        assertEquals("TARGET_VERSION_MISMATCH", item.reason());
        assertEquals("model:2", item.targetVersion());
        assertEquals("write failed", item.failureReason());
        assertEquals(ReplayStatus.ACCEPTED, service.replay(reference, 1L).status());
        verify(publisher).publishRecovery(1L, "model:2", "write failed");
        assertEquals(ReplayStatus.ALREADY_COMPLETED, service.replay(reference, 1L).status());
        verify(publisher, never()).publish(anyLong());
    }

    private ContentVectorDeadLetters.Entry entry(Long id) {
        return new ContentVectorDeadLetters.Entry(reference, "message", id, 100L, 3, null, null);
    }

    private ContentDoc content() {
        var content = new ContentDoc();
        content.setStatus("published");
        content.setVisibility("public");
        content.setTitle("标题");
        return content;
    }

    private MultiGetResponseItem<ContentDoc> result(long id, ContentDoc content) {
        return new MultiGetResponseItem.Builder<ContentDoc>().result(r -> r.index("content").id(Long.toString(id))
                .found(content != null).source(content)).build();
    }

    private MgetResponse<ContentDoc> response(ContentDoc content) {
        return MgetResponse.of(b -> b.docs(result(1, content)));
    }
}
