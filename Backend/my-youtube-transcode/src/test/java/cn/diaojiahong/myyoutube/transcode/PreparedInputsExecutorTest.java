package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.api.*;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.cover.*;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparationStorage;
import cn.diaojiahong.myyoutube.transcode.task.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparedInputsExecutorTest {
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final ContentArtifactStore artifacts = mock(ContentArtifactStore.class);
    final OcrPort ocr = mock(OcrPort.class);
    final ModelCallGuard guard = new ModelCallGuard(new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new org.springframework.mock.env.MockEnvironment());
    final String prefix = "media-prepared/9/101/";

    PreparedMedia.Snapshot snapshot(boolean video) {
        var items = new ArrayList<Item>();
        ContentMedia input;
        String cover = null;
        if (video) {
            input = new ContentMedia("video", new VideoMedia("uploaded-video", 0), null);
            items.add(new Item("VIDEO", 0, prefix + "video", prefix + "video", 0, 0, 0));
            for (int i = 0; i < 8; i++) items.add(new Item("FRAME", i, prefix + "video", prefix + "frame-" + i, i * 100L, 80, 60));
        } else {
            input = new ContentMedia("dynamic", null, new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                    List.of(new DynamicMedia.Image("upload-a", 0, 3000, .5, .5), new DynamicMedia.Image("upload-b", 3000, 3000, .5, .5)), null, 6000));
            cover = "upload-b";
            for (int i = 0; i < 2; i++) {
                items.add(new Item("IMAGE", i, prefix + "image-" + i, prefix + "image-" + i, i * 3000L, 0, 0));
                items.add(new Item("OCR_IMAGE", i, prefix + "image-" + i, prefix + "ocr-" + i, i * 3000L, 80, 60));
            }
        }
        return PreparedMedia.freeze(new Request(101L, 1L, 2, 9L, input, cover),
                new Manifest(101L, 1L, 2, 9L, "a".repeat(64), items, video ? 1000 : 6000, false));
    }
    MediaTaskMessage message(boolean video) {
        var snapshot = snapshot(video);
        return MediaTaskMessage.builder().pipelineId(100L).ownerId(1L).taskId(7L).userId(9L).attempt(1)
                .objectKey(video ? snapshot.media().video().objectKey() : null)
                .input(new PipelineContracts.Input(100L, 1L, 9L, "title", "description", "", snapshot.coverObjectKey(), snapshot.media(), 1))
                .dependencies(Map.of()).build();
    }
    @BeforeEach void setup() {
        when(storage.signGetUrl(anyString(), anyLong())).thenAnswer(call -> "signed:" + call.getArgument(0));
        when(ocr.recognize(any())).thenAnswer(call -> {
            OcrPort.Request request = call.getArgument(0);
            return new OcrPort.Response(request.urls().stream().map(url -> new OcrPort.Item(url, "text", null)).toList());
        });
        when(artifacts.write(anyLong(), eq("OCR"), anyInt(), any())).thenReturn("ocr-artifact");
    }
    ContentArtifacts.Ocr recognize(boolean video) throws Exception {
        new OcrTaskExecutor(ocr, guard, storage, artifacts, new PipelineProperties()).execute(message(video));
        var captured = org.mockito.ArgumentCaptor.forClass(ContentArtifacts.Ocr.class);
        verify(artifacts).write(eq(100L), eq("OCR"), eq(1), captured.capture());
        return captured.getValue();
    }
    @Test void dynamicOcrUsesOnlySmallImagesAndPreservesOriginalMapping() throws Exception {
        var result = recognize(false); assertEquals(2, result.frames().size());
        var second = result.frames().get(1);
        assertEquals(prefix + "image-1", second.objectKey()); assertEquals(prefix + "ocr-1", second.derivedObjectKey());
        assertEquals(3000, second.timeMs()); assertEquals(1, second.imageIndex());
        verify(storage, never()).signGetUrl(contains("image-"), anyLong());
        PreparedMedia.validateOcr(snapshot(false).media(), result);
    }
    @Test void videoOcrUsesAllEightPreparedFramesWithoutStorageWritesOrVideoAccess() throws Exception {
        var result = recognize(true); assertEquals(8, result.frames().size());
        for (int i = 0; i < 8; i++) {
            assertEquals(prefix + "frame-" + i, result.frames().get(i).objectKey());
            assertEquals(i * 100L, result.frames().get(i).timeMs());
        }
        verify(storage, never()).signGetUrl(eq(prefix + "video"), anyLong());
        verify(storage, never()).uploadForObjectKey(any(), anyString(), anyLong(), anyString());
        PreparedMedia.validateOcr(snapshot(true).media(), result);
        var wrong = new ContentArtifacts.Ocr(List.of(new ContentArtifacts.Frame(0, 0, "wrong", "text", "wrong")));
        assertThrows(IllegalArgumentException.class, () -> PreparedMedia.validateOcr(snapshot(true).media(), wrong));
    }
    @Test void silentVideoCreatesExplicitEmptyAsrWithoutCallingModel() {
        var asr = mock(AsrPort.class);
        when(artifacts.write(anyLong(), eq("ASR"), anyInt(), any())).thenReturn("empty-segments");
        var result = StructuredJson.object(new AsrTaskExecutor(asr, guard, storage, artifacts).execute(message(true)));
        assertTrue(result.get("noAudio").getAsBoolean()); assertTrue(result.get("speechEmpty").getAsBoolean());
        verifyNoInteractions(asr); verify(storage, never()).signGetUrl(anyString(), anyLong());
    }
    @Test void preparedCoverReferencesSelectedFrameAndDynamicSelectedOriginal() {
        var generator = mock(VideoCoverGenerator.class);
        when(generator.selectPrepared(any())).thenReturn(prefix + "frame-3");
        var executor = new CoverTaskExecutor(generator);
        assertEquals(prefix + "frame-3", StructuredJson.object(executor.execute(message(true))).get("coverObjectKey").getAsString());
        assertEquals(prefix + "image-1", StructuredJson.object(executor.execute(message(false))).get("coverObjectKey").getAsString());
        verify(generator, never()).generate(anyLong(), anyLong(), anyString());
    }
    @Test void preparedCoverScoresBoundedPreviewsAndReturnsOriginalKeyWithoutUpload() throws Exception {
        var preparedStorage = mock(PreparationStorage.class);
        when(preparedStorage.inspect(anyString())).thenAnswer(call -> new PreparationStorage.Source(call.getArgument(0), null, "etag", 100));
        doAnswer(call -> {
            Path file = call.getArgument(1);
            var image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); graphics.setColor(java.awt.Color.GRAY); graphics.fillRect(0, 0, 1600, 900); graphics.dispose();
            javax.imageio.ImageIO.write(image, "jpg", file.toFile()); image.flush(); return null;
        }).when(preparedStorage).download(any(), any(), anyLong());
        var selector = mock(CoverVisionSelector.class);
        when(selector.pickBest(anyList())).thenAnswer(call -> {
            List<cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil.FrameScore> candidates = call.getArgument(0);
            assertEquals(8, candidates.size());
            for (var candidate : candidates) {
                var image = javax.imageio.ImageIO.read(candidate.framePath().toFile());
                assertTrue(Math.max(image.getWidth(), image.getHeight()) <= 512); image.flush();
            }
            return 3;
        });
        var generator = new VideoCoverGenerator(storage, selector, preparedStorage);
        assertEquals(prefix + "frame-3", generator.selectPrepared(snapshot(true).media().preparation()));
        verifyNoInteractions(storage); verify(preparedStorage, times(8)).download(any(), any(), anyLong());
        verify(preparedStorage, never()).upload(any(), anyString());
    }
}
