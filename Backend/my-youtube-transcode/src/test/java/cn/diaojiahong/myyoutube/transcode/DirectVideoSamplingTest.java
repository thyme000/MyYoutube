package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.api.OcrPort;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.transcode.task.OcrTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectVideoSamplingTest {
    @TempDir Path work;
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final OcrPort ocr = mock(OcrPort.class);
    final ContentArtifactStore artifacts = mock(ContentArtifactStore.class);
    final ModelCallGuard guard = new ModelCallGuard(new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new org.springframework.mock.env.MockEnvironment());
    final OcrTaskExecutor executor = new OcrTaskExecutor(ocr, guard, storage, artifacts, new FrameProcessingSettings());

    MediaTaskMessage message() {
        var media = new ContentMedia("video", new VideoMedia("video", 50000), null);
        return MediaTaskMessage.builder().pipelineId(100L).taskId(7L).userId(9L).attempt(1).objectKey("video")
                .input(new PipelineContracts.Input(100L, 1L, 9L, "title", "description", "", null, media, 1)).build();
    }

    List<FFmpegUtil.FrameScore> frames(Set<Integer> missing) throws Exception {
        var frames = new ArrayList<FFmpegUtil.FrameScore>();
        for (int i = 0; i < 10; i++) if (!missing.contains(i)) {
            var path = Files.write(work.resolve("cover-candidate-" + i + ".jpg"), new byte[]{1, 2, 3});
            frames.add(new FFmpegUtil.FrameScore(path, i * 5, 1, 1, 1, 100, 20, 1));
        }
        return frames;
    }

    @Test void directOcrFreezesEightActualSlotsForReview() throws Exception {
        var frames = frames(Set.of(2, 7));
        when(storage.signGetUrl(anyString(), anyLong())).thenAnswer(call -> "signed:" + call.getArgument(0));
        var uploaded = new java.util.concurrent.atomic.AtomicInteger();
        when(storage.uploadForObjectKey(any(), anyString(), anyLong(), anyString())).thenAnswer(call -> "image-" + uploaded.getAndIncrement());
        when(ocr.recognize(any())).thenAnswer(call -> {
            OcrPort.Request request = call.getArgument(0);
            return new OcrPort.Response(request.urls().stream().map(url -> new OcrPort.Item(url, "", null)).toList());
        });
        when(artifacts.write(anyLong(), eq("OCR"), anyInt(), any())).thenReturn("ocr");
        try (var ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
            ffmpeg.when(() -> FFmpegUtil.probeVideo(anyString(), any())).thenReturn(new FFmpegUtil.VideoProbe(50000, 80, 60));
            ffmpeg.when(() -> FFmpegUtil.extractAndScoreCandidates(anyString(), any(), any())).thenReturn(frames);
            var result = StructuredJson.object(executor.execute(message()));
            assertEquals(8, result.get("videoFrameCount").getAsInt());
            assertEquals(VideoFrameSampling.plan(10, 80), JsonUtil.GSON.fromJson(result.get("videoSampling"), VideoFrameSampling.Plan.class));
            var captured = ArgumentCaptor.forClass(ContentArtifacts.Ocr.class);
            verify(artifacts).write(eq(100L), eq("OCR"), eq(1), captured.capture());
            assertEquals(List.of(0, 1, 3, 4, 5, 6, 8, 9), captured.getValue().frames().stream().map(ContentArtifacts.Frame::imageIndex).toList());
            verify(ocr, times(4)).recognize(any());
            assertEquals(8, uploaded.get());
        }
    }

    @Test void directOcrRejectsTooFewOrConsecutiveMissingFramesBeforeUpload() throws Exception {
        for (var missing : List.of(Set.of(2, 5, 7), Set.of(2, 3))) {
            var frames = frames(missing);
            when(storage.signGetUrl(anyString(), anyLong())).thenReturn("source");
            try (var ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
                ffmpeg.when(() -> FFmpegUtil.probeVideo(anyString(), any())).thenReturn(new FFmpegUtil.VideoProbe(50000, 80, 60));
                ffmpeg.when(() -> FFmpegUtil.extractAndScoreCandidates(anyString(), any(), any())).thenReturn(frames);
                assertThrows(VideoFrameSampling.Rejected.class, () -> executor.execute(message()));
                verify(storage, never()).uploadForObjectKey(any(), anyString(), anyLong(), anyString());
                verifyNoInteractions(ocr, artifacts);
            }
        }
    }
}
