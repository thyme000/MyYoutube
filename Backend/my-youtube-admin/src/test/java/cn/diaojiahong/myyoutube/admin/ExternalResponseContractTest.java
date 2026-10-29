package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.admin.controller.AdminContentVectorDeadLetterController;
import cn.diaojiahong.myyoutube.admin.controller.AdminHotSearchHistoryController;
import cn.diaojiahong.myyoutube.admin.controller.AdminMediaRetryController;
import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExternalResponseContractTest {
    final MediaRetryPort retries = mock(MediaRetryPort.class);
    final HotSearchHistoryPort history = mock(HotSearchHistoryPort.class);
    final ContentVectorDeadLetterPort deadLetters = mock(ContentVectorDeadLetterPort.class);
    final long id = 9_007_199_254_740_993L;
    MockMvc mvc;

    @BeforeEach void setup() {
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new AdminMediaRetryController(retries, AuthorizationFixture.authorization()),
                        new AdminHotSearchHistoryController(history, AuthorizationFixture.authorization()), new AdminContentVectorDeadLetterController(deadLetters, AuthorizationFixture.authorization()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.setUser("1");
    }

    @AfterEach void clear() { UserContext.removeUser(); }

    MediaRetryPort.Batch batch() {
        return new MediaRetryPort.Batch(id, MediaRetryBatchStatus.RUNNING, 1, 0, 1, 0, 0, 0, 1, 1, null, null, null);
    }

    @Test void acceptedAndRejectedAdmissionsRetainTheFullDecisionAndLargeBatchId() throws Exception {
        when(retries.create(any(), eq(1L))).thenReturn(
                new MediaRetryPort.Admission(true, "RETRY_BATCH_CREATED", "已登记", batch()),
                new MediaRetryPort.Admission(false, "RETRY_BATCH_RUNNING", "当前已有批次在运行", batch()));
        mvc.perform(post("/admin/media-retry-batches").contentType("application/json")
                        .content("{\"requestId\":\"one\",\"taskIds\":[\"9007199254740993\"]}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.code").value("RETRY_BATCH_CREATED"))
                .andExpect(jsonPath("$.data.batch.batchId").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.data").doesNotExist());
        mvc.perform(post("/admin/media-retry-batches").contentType("application/json")
                        .content("{\"requestId\":\"two\",\"taskIds\":[\"9007199254740993\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.code").value("RETRY_BATCH_RUNNING"))
                .andExpect(jsonPath("$.data.batch.batchId").value(Long.toString(id)));
    }

    @Test void retryQueriesKeepEmptyPagesWithAContinuationAndNullCurrentBatch() throws Exception {
        when(retries.query(any())).thenReturn(new CursorPage<>(List.of(), id));
        when(retries.history(id, 20)).thenReturn(new CursorPage<>(List.of(batch()), null));
        mvc.perform(get("/admin/media-tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(Long.toString(id)));
        mvc.perform(get("/admin/media-retry-batches").param("cursor", Long.toString(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].batchId").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
        mvc.perform(get("/admin/media-retry-batches/current")).andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"操作成功\",\"data\":null}"));
    }

    @Test void retryDetailRoutesWrapObjectsAndListsOnce() throws Exception {
        when(retries.batch(id)).thenReturn(batch());
        when(retries.affected(id)).thenReturn(List.of(new MediaRetryPort.Affected(id, MediaTaskStatus.RUNNING, 1, null)));
        when(retries.items(id)).thenReturn(List.of(new MediaRetryPort.Item(id, MediaRetryItemState.RUNNING, 0, 1, null)));
        var path = "/admin/media-retry-batches/" + id;
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.batchId").value(Long.toString(id)));
        for (String suffix : List.of("tasks", "items")) {
            mvc.perform(get(path + "/" + suffix)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].taskId").value(Long.toString(id)));
        }
    }

    @Test void historyKeepsOpaqueCursorAndDeadLetterPageKeepsItsEnabledFlag() throws Exception {
        when(history.events("2026-10-20", "g", id, null, "100.5", 20))
                .thenReturn(new CursorPage<>(List.of(), "100." + id));
        mvc.perform(get("/admin/hot-search/events").param("date", "2026-10-20").param("groupId", "g")
                        .param("keywordId", Long.toString(id)).param("cursor", "100.5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextCursor").value("100." + id))
                .andExpect(jsonPath("$.data.items").isEmpty());
        when(deadLetters.query(null, 20)).thenReturn(new ContentVectorDeadLetterPort.Page(List.of(), null, false));
        mvc.perform(get("/admin/dead-letters/content-vectors/messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.enabled").value(false));
    }

    @ParameterizedTest
    @CsvSource({"ACCEPTED,202,200", "ALREADY_COMPLETED,200,200", "INELIGIBLE,409,40901", "DISABLED,409,40901",
            "NOT_FOUND,404,40401", "INVALID_MESSAGE,400,40001", "STATE_UNAVAILABLE,503,50301", "DELIVERY_UNCONFIRMED,503,50301"})
    void replayDecisionsPreserveHttpMeaningAndStructuredDetails(ContentVectorDeadLetterPort.ReplayStatus decision,
                                                                int status, int code) throws Exception {
        when(deadLetters.replay(any(), eq(1L)))
                .thenReturn(new ContentVectorDeadLetterPort.ReplayResult(decision, id, "业务决定"));
        mvc.perform(post("/admin/dead-letters/content-vectors/messages/replay").contentType("application/json")
                        .content("{\"brokerName\":\"broker\",\"queueId\":0,\"offset\":\"9007199254740993\"}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data.status").value(decision.name()))
                .andExpect(jsonPath("$.data.contentId").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.message").value("业务决定"));
        verify(deadLetters).replay(new ContentVectorDeadLetterPort.Reference("broker", 0, id), 1L);
        verifyNoMoreInteractions(deadLetters);
    }
}
