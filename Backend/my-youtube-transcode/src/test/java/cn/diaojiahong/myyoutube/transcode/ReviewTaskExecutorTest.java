package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.ContentArtifactStore;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.ReviewSpec;
import cn.diaojiahong.myyoutube.common.media.ReviewVerdict;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.task.ReviewTaskExecutor;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewTaskExecutorTest {

    private final ContentPipelinePort video = mock(ContentPipelinePort.class);
    private final ContentArtifactStore artifacts = mock(ContentArtifactStore.class);
    private final S3StorageUtil storage = mock(S3StorageUtil.class);
    private final LlmPort llm = mock(LlmPort.class);
    private final ModelCallGuard guard = new ModelCallGuard(new StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new MockEnvironment());
    private final ReviewTaskExecutor executor = new ReviewTaskExecutor(video, artifacts, storage, llm, guard);

    private ReviewSpec policy(boolean enabled, boolean vision, int batchSize) {
        return new ReviewSpec(enabled, "test", "v1", "rules", 0.2, 0.9, 12000, vision, batchSize);
    }

    private MediaTaskMessage input(boolean vision, int batchSize, int frameCount) {
        when(video.reviewSpec(100L, 1)).thenReturn(policy(true, vision, batchSize));
        when(artifacts.read("asr", ContentArtifacts.Transcript.class))
                .thenReturn(new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(0, 1000, "字幕"))));
        when(artifacts.read("ocr", ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(
                IntStream.range(0, frameCount).mapToObj(i -> new ContentArtifacts.Frame(i, i * 100L, "frame-" + i, "文字")).toList()));
        when(artifacts.read("summary", ContentArtifacts.Summary.class))
                .thenReturn(new ContentArtifacts.Summary("摘要", List.of()));

        return MediaTaskMessage.builder().pipelineId(100L).attempt(1)
                .input(new Input(100L, 1L, 9L, "标题", "简介", "", null, new cn.diaojiahong.myyoutube.common.media.ContentMedia("video", new cn.diaojiahong.myyoutube.common.media.VideoMedia("processed.mp4", 1000), null), 1))
                .dependencies(Map.of("ASR", "{\"segmentsKey\":\"asr\"}", "OCR", "{\"artifactKey\":\"ocr\"}",
                        "SUMMARY", "{\"artifactKey\":\"summary\"}")).build();
    }

    private LlmPort.ChatResponse response(String decision, double risk, double confidence) {
        return new LlmPort.ChatResponse("""
                {"decision":"%s","riskScore":%s,"confidence":%s,"reason":"审核依据","evidence":[]}
                """.formatted(decision, risk, confidence), "test", "stop", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"summary\":\"unterminated",
            "{\"summary\":\"wrong output contract\"}",
            "{\"decision\":\"PASS\",\"decision\":\"REJECT\",\"riskScore\":0.1,\"confidence\":0.99,\"reason\":\"ok\",\"evidence\":[]}",
            "{\"decision\":\"PASS\",\"riskScore\":\"0.1\",\"confidence\":0.99,\"reason\":\"ok\",\"evidence\":[]}",
            "{\"decision\":\"PASS\",\"riskScore\":0.1,\"confidence\":0.99,\"reason\":\"ok\",\"evidence\":[]} trailing"
    })
    void malformedModelOutputIsAnLlmFailureRatherThanAReviewVerdict(String content) {
        var message = input(false, 4, 0);
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(content, "test", "stop", null));
        var error = assertThrows(cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure.class,
                () -> executor.execute(message));
        assertEquals("LLM", error.resource());
        assertTrue(error.getMessage().contains("JSON"));
        assertNotNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "tool_calls", "content_filter"})
    void nonStopResponseCannotPassEvenWithValidJson(String finishReason) {
        var message = input(false, 4, 0);
        var response = response("PASS", .1, .99);
        response.setFinishReason(finishReason);
        when(llm.chat(any())).thenReturn(response);
        var error = assertThrows(cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure.class,
                () -> executor.execute(message));
        assertEquals("审核模型输出未正常结束", error.getMessage());
    }

    @Test void reviewRequestsTheReviewSchemaForTextAndFrames() {
        var message = input(true, 4, 8);
        when(llm.chat(any())).thenReturn(response("PASS", .1, .99));
        executor.execute(message);
        var requests = ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(3)).chat(requests.capture());
        for (var request : requests.getAllValues()) {
            var format = cn.diaojiahong.myyoutube.common.utils.JsonUtil.GSON.toJsonTree(request.getResponseFormat()).getAsJsonObject();
            assertEquals("json_schema", format.get("type").getAsString());
            var schema = format.getAsJsonObject("json_schema");
            assertEquals("content_review", schema.get("name").getAsString());
            assertTrue(schema.get("strict").getAsBoolean());
            assertEquals(java.util.Set.of("decision", "riskScore", "confidence", "reason", "evidence"),
                    schema.getAsJsonObject("schema").getAsJsonObject("properties").keySet());
        }
    }

    @Test void aggregateUsesPolicyVersionInsteadOfModelClaim() {
        var message = input(false, 4, 0);
        String version = "a".repeat(64);
        when(video.reviewSpec(100L, 1)).thenReturn(new ReviewSpec(true, "test", version, "rules", 0.2, 0.9, 12000, false, 4));
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(
                "{\"decision\":\"PASS\",\"riskScore\":0.1,\"confidence\":0.99,\"reason\":\"安全\",\"evidence\":[],\"rulesVersion\":\"fake\"}", "test", "stop", null));
        var result = cn.diaojiahong.myyoutube.common.media.StructuredJson.object(executor.execute(message));
        assertEquals(version, result.get("rulesVersion").getAsString());
    }

    @Test
    void disabledPolicyCancelsQueuedReviewBeforeReadingArtifactsOrCallingModel() {
        when(video.reviewSpec(100L, 1)).thenReturn(policy(false, false, 4));

        assertEquals("{\"cancelled\":true}", executor.execute(MediaTaskMessage.builder().pipelineId(100L).attempt(1).build()));
        verifyNoInteractions(artifacts, storage, llm);
    }

    @Test
    void disabledVisionReviewsTextAndKeepsPassWithoutFetchingImages() {
        var message = input(false, 4, 0);
        when(llm.chat(any())).thenReturn(response("PASS", 0.1, 0.99));

        var verdict = ReviewVerdict.parse(executor.execute(message), "test");

        assertTrue(verdict.passes(0.2, 0.9));
        assertEquals("全部文本证据批次通过机器初审", verdict.reason());
        assertTrue(verdict.evidence().get(1).getAsJsonObject().get("skipped").getAsBoolean());
        var request = ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(1)).chat(request.capture());
        assertTrue(request.getValue().getMessages().get(1).getParts().stream().allMatch(p -> "text".equals(p.getType())));
        verifyNoInteractions(storage);
    }

    @Test
    void disabledVisionPreservesTextRejection() {
        var message = input(false, 4, 8);
        when(llm.chat(any())).thenReturn(response("REJECT", 0.9, 0.99));

        var verdict = ReviewVerdict.parse(executor.execute(message), "test");

        assertEquals("REJECT", verdict.decision());
        assertFalse(verdict.passes(0.2, 0.9));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 8})
    void enabledVisionBatchesAllFramesIncludingFinalPartialBatch(int batchSize) {
        var message = input(true, batchSize, 8);
        when(llm.chat(any())).thenReturn(response("PASS", 0.1, 0.99));
        when(storage.signGetUrl(anyString(), eq(600L))).thenAnswer(call -> "http://local/" + call.getArgument(0));

        var verdict = ReviewVerdict.parse(executor.execute(message), "test");

        int frameBatches = (8 + batchSize - 1) / batchSize;
        assertTrue(verdict.passes(0.2, 0.9));
        assertEquals(1 + frameBatches, verdict.evidence().size());
        var requests = ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(1 + frameBatches)).chat(requests.capture());
        var imageBatches = requests.getAllValues().stream().skip(1)
                .map(r -> r.getMessages().get(1).getParts().stream()
                        .filter(p -> "image_url".equals(p.getType())).map(p -> p.getImageUrl().getUrl()).toList()).toList();
        assertTrue(imageBatches.stream().allMatch(batch -> !batch.isEmpty() && batch.size() <= batchSize));
        assertEquals(IntStream.range(0, 8).mapToObj(i -> "http://local/frame-" + i).toList(),
                imageBatches.stream().flatMap(List::stream).toList());
        verify(storage, times(8)).signGetUrl(anyString(), eq(600L));
    }

    @Test
    void aggregationKeepsMaximumRiskAndMinimumConfidenceAcrossTextAndFrames() {
        var message = input(true, 4, 8);
        when(llm.chat(any())).thenReturn(response("PASS", 0.1, 0.99),
                response("PASS", 0.3, 0.95), response("PASS", 0.2, 0.8));

        var verdict = ReviewVerdict.parse(executor.execute(message), "test");

        assertEquals(0.3, verdict.riskScore());
        assertEquals(0.8, verdict.confidence());
        assertFalse(verdict.passes(0.2, 0.9));
    }

    @Test
    void enabledVisionFailurePropagatesForSchedulerRetry() {
        var message = input(true, 4, 8);
        when(llm.chat(any())).thenReturn(response("PASS", 0.1, 0.99))
                .thenThrow(new IllegalStateException("vision unavailable"));

        assertThrows(IllegalStateException.class, () -> executor.execute(message));
        verify(llm, times(2)).chat(any());
    }

    @Test
    void enabledVisionRejectsMissingFrames() {
        var message = input(true, 4, 7);
        when(llm.chat(any())).thenReturn(response("PASS", 0.1, 0.99));

        assertThrows(IllegalArgumentException.class, () -> executor.execute(message));
        verifyNoInteractions(storage);
    }
}
