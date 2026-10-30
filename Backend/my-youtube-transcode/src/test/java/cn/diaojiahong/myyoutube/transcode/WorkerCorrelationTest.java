package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.config.FeignRetryConfiguration;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.service.*;
import cn.diaojiahong.myyoutube.transcode.task.MediaTaskExecutor;
import feign.RequestTemplate;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerCorrelationTest {
    @AfterEach
    void clear() {
        MDC.clear();
    }

    MediaTaskMessage message() {
        return MediaTaskMessage.builder().taskId(9007199254740993L).pipelineId(100L).ownerId(10L)
                .phase("CONTENT").taskType("ASR").attempt(4).input(new PipelineContracts.Input(100L, 10L, 9L,
                        "", "", "", null, new ContentMedia("video", new VideoMedia("object", 1000), null), 1)).build();
    }

    @Test
    void workerPropagatesHeadersAndRestoresContextAfterFailure() throws Exception {
        MediaTaskService tasks = mock(MediaTaskService.class);
        MediaTaskMessage message = message();
        when(tasks.claim(message)).thenReturn(message);
        MediaTaskExecutor executor = mock(MediaTaskExecutor.class);
        when(executor.type()).thenReturn(MediaTaskType.ASR);
        when(executor.execute(message)).thenAnswer(call -> {
            var request = new RequestTemplate();
            new FeignRetryConfiguration().businessCorrelationHeaders().apply(request);
            assertEquals(List.of("9007199254740993"), List.copyOf(request.headers().get("X-Task-Id")));
            assertEquals(List.of("4"), List.copyOf(request.headers().get("X-Task-Attempt")));
            assertEquals(List.of("trace-1"), List.copyOf(request.headers().get("X-Business-Trace-Id")));
            assertEquals("10", MDC.get("contentId"));
            throw new IllegalStateException("model unavailable");
        });
        MDC.put("traceId", "trace-1");
        MDC.put("taskId", "outer");
        var worker = new MediaTaskWorker(tasks, List.of(executor));
        try {
            worker.execute(message);
            assertEquals("outer", MDC.get("taskId"));
            assertNull(MDC.get("attempt"));
            verify(tasks).completeAndAdvance(eq(message), isNull(), contains("model unavailable"));
        } finally {
            worker.close();
        }
    }

    @Test
    void admissionRejectionUsesDeferralInsteadOfExecutionFailure() throws Exception {
        MediaTaskService tasks = mock(MediaTaskService.class);
        MediaTaskMessage message = message();
        when(tasks.claim(message)).thenReturn(message);
        MediaTaskExecutor executor = mock(MediaTaskExecutor.class);
        when(executor.type()).thenReturn(MediaTaskType.ASR);
        var unavailable = new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_RECOVERING", 30, null);
        when(executor.execute(message)).thenThrow(unavailable);
        var worker = new MediaTaskWorker(tasks, List.of(executor));
        try {
            worker.execute(message);
            verify(tasks).deferAndAdvance(message, unavailable);
            verify(tasks, never()).completeAndAdvance(any(), any(), any());
        } finally {
            worker.close();
        }
    }

    @Test
    void completionExceptionStillCleansThreadContext() throws Exception {
        MediaTaskService tasks = mock(MediaTaskService.class);
        MediaTaskMessage message = message();
        when(tasks.claim(message)).thenReturn(message);
        MediaTaskExecutor executor = mock(MediaTaskExecutor.class);
        when(executor.type()).thenReturn(MediaTaskType.ASR);
        when(executor.execute(message)).thenReturn("{}");
        doThrow(new IllegalStateException("database unavailable")).when(tasks).completeAndAdvance(message, "{}", null);
        var worker = new MediaTaskWorker(tasks, List.of(executor));
        try {
            assertThrows(IllegalStateException.class, () -> worker.execute(message));
            assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
        } finally {
            worker.close();
        }
    }
}
