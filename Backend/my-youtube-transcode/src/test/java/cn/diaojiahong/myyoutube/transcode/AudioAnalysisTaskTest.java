package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.resilience.*;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.api.AsrPort;
import cn.diaojiahong.myyoutube.transcode.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioAnalysisTaskTest {
    final ContentArtifactStore artifacts = mock(ContentArtifactStore.class);
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final AsrPort asr = mock(AsrPort.class);
    final LlmPort llm = mock(LlmPort.class);
    final ModelCallGuard guard = mock(ModelCallGuard.class);
    final AudioAnalysisProperties properties = new AudioAnalysisProperties();
    @TempDir Path directory;
    AudioReviewTaskExecutor review;
    @BeforeEach void setup() throws Exception {
        when(guard.call(anyString(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(storage.signGetUrl(anyString(), anyLong())).thenReturn("http://localhost/source");
        when(storage.presignPutUrl(anyString(), anyLong())).thenReturn("http://localhost/output");
        var rules = directory.resolve("rules.txt"); Files.writeString(rules, "exact:坏\n");
        review = new AudioReviewTaskExecutor(artifacts, llm, guard, new PromptTemplates(), properties, rules.toUri().toString());
    }
    MediaTaskMessage task(MediaTaskType type) {
        return MediaTaskMessage.builder().ownerId(501L).userId(9L).attempt(1)
                .audioAnalysisInput(new AudioAnalysisContracts.Request(501L, 9L, "长标题不能绕过短文本限制", "media-prepared/9/501/audio-source", 2000,
                        type, type == MediaTaskType.AUDIO_ASR ? null : "audio-artifacts/9/501/asr/1/out.json")).build();
    }
    ContentArtifacts.Transcript transcript(String text) {
        return new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(0, 1000, text)));
    }
    AudioAnalysisContracts.ScreenResult screen(String text) {
        when(artifacts.read(anyString(), eq(ContentArtifacts.Transcript.class))).thenReturn(transcript(text));
        return JsonUtil.fromJson(review.execute(task(MediaTaskType.AUDIO_REVIEW)), AudioAnalysisContracts.ScreenResult.class);
    }
    @Test void emptyAndPunctuationOnlyRecognitionSkipModelEvenWithLongTitle() {
        for (String text : List.of("", " ，。！？...", "abc", "你好")) {
            var result = screen(text); assertTrue(result.modelSkipped()); assertTrue(result.verdicts().isEmpty());
        }
        verifyNoInteractions(llm); verifyNoInteractions(storage, asr);
    }
    @Test void shortTranscriptStillRunsLocalRules() {
        var result = screen("坏"); assertTrue(result.localBlocked()); assertTrue(result.modelSkipped()); verifyNoInteractions(llm);
    }
    @Test void boundaryLengthRunsTextOnlyModelAndRetainsVerdictAndPromptVersion() {
        var response = new LlmPort.ChatResponse("{\"decision\":\"PASS\",\"riskScore\":0,\"confidence\":1,\"reason\":\"文本正常\",\"evidence\":[]}", "model");
        response.setFinishReason("stop"); when(llm.chat(any())).thenReturn(response);
        var result = screen("一二三四五六七八"); assertEquals(8, result.effectiveCharacters()); assertFalse(result.modelSkipped());
        assertEquals("PASS", result.verdicts().get(0).decision()); assertNotNull(result.verdicts().get(0).rulesVersion());
        verify(llm).chat(argThat(q -> q.getMessages().stream().allMatch(m -> m.getParts() == null)));
        verifyNoInteractions(storage, asr);
    }
    @Test void invalidModelOutputFailsInsteadOfLookingLikeSkippedOrPassed() {
        var response = new LlmPort.ChatResponse("not json", "model"); response.setFinishReason("stop"); when(llm.chat(any())).thenReturn(response);
        assertThrows(ModelCallFailure.class, () -> screen("一二三四五六七八"));
    }
    @Test void entireEvidenceIsChunkedWithoutDroppingTheTail() {
        properties.setChunkCharacters(1024);
        var response = new LlmPort.ChatResponse("{\"decision\":\"UNCERTAIN\",\"riskScore\":0.5,\"confidence\":0.5,\"reason\":\"人工核对\",\"evidence\":[]}", "m");
        response.setFinishReason("stop"); when(llm.chat(any())).thenReturn(response);
        var result = screen("音".repeat(2500) + "尾部"); assertEquals(3, result.verdicts().size());
        verify(llm).chat(argThat(q -> q.getMessages().get(1).getContent().contains("尾部")));
    }
    @Test void successfulEmptyAsrIsDistinctFromMissingResponseOrArtifact() {
        var executor = new AudioAsrTaskExecutor(asr, guard, storage, artifacts);
        when(asr.transcribe(any())).thenReturn(new AsrPort.Response(2000, 0, true));
        when(artifacts.read(anyString(), eq(ContentArtifacts.Transcript.class))).thenReturn(new ContentArtifacts.Transcript(List.of()));
        var result = JsonUtil.fromJson(executor.execute(task(MediaTaskType.AUDIO_ASR)), AudioAnalysisContracts.TranscriptResult.class);
        assertEquals(0, result.segmentCount()); result.validate(task(MediaTaskType.AUDIO_ASR).getAudioAnalysisInput());
        verify(asr).transcribe(argThat(q -> q.title().isEmpty() && q.description().isEmpty()));
        when(asr.transcribe(any())).thenReturn(null); assertThrows(IllegalStateException.class, () -> executor.execute(task(MediaTaskType.AUDIO_ASR)));
        when(asr.transcribe(any())).thenReturn(new AsrPort.Response(0, 0, true));
        assertThrows(IllegalStateException.class, () -> executor.execute(task(MediaTaskType.AUDIO_ASR)));
        when(asr.transcribe(any())).thenReturn(new AsrPort.Response(2000, 0, true));
        when(artifacts.read(anyString(), eq(ContentArtifacts.Transcript.class))).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> executor.execute(task(MediaTaskType.AUDIO_ASR)));
    }
    @Test void invalidTimestampOrDurationOverflowIsTechnicalContractFailure() {
        var executor = new AudioAsrTaskExecutor(asr, guard, storage, artifacts);
        when(asr.transcribe(any())).thenReturn(new AsrPort.Response(2000, 1, false));
        when(artifacts.read(anyString(), eq(ContentArtifacts.Transcript.class)))
                .thenReturn(new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(0, 60000, "错误时长"))));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(task(MediaTaskType.AUDIO_ASR)));
        when(artifacts.read(anyString(), eq(ContentArtifacts.Transcript.class)))
                .thenReturn(new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(0, 0, "缺少有效时间戳"))));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(task(MediaTaskType.AUDIO_ASR)));
    }
}
