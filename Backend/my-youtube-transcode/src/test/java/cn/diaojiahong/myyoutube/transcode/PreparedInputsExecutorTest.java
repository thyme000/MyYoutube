package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.api.*;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.cover.*;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparationStorage;
import cn.diaojiahong.myyoutube.transcode.task.*;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
            new org.springframework.mock.env.MockEnvironment());
    final String prefix = "media-prepared/9/101/";

    PreparedMedia.Snapshot snapshot(boolean video) {
        return snapshot(video, 8);
    }

    PreparedMedia.Snapshot snapshot(boolean video, int frameCount) {
        var items = new ArrayList<Item>();
        ContentMedia input;
        String cover = null;
        if (video) {
            input = new ContentMedia("video", new VideoMedia("uploaded-video", 0), null);
            items.add(new Item("VIDEO", 0, prefix + "video", prefix + "video", 0, 0, 0));
            for (int i = 0; i < frameCount; i++) items.add(new Item("FRAME", i, prefix + "video", prefix + "frame-" + i, i * 100L, 80, 60));
        } else {
            input = new ContentMedia("dynamic", null, new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                    List.of(new DynamicMedia.Image("upload-a", 0, 3000, .5, .5), new DynamicMedia.Image("upload-b", 3000, 3000, .5, .5)),
                    null,
                    6000));
            cover = "upload-b";
            for (int i = 0; i < 2; i++) {
                items.add(new Item("IMAGE", i, prefix + "image-" + i, prefix + "image-" + i, i * 3000L, 0, 0));
                items.add(new Item("OCR_IMAGE", i, prefix + "image-" + i, prefix + "ocr-" + i, i * 3000L, 80, 60));
            }
        }
        return PreparedMedia.freeze(new Request(101L, 1L, 2, 9L, input, cover),
                new Manifest(
                        101L,
                        1L,
                        2,
                        9L,
                        "a".repeat(64),
                        items,
                        video ? frameCount * 5000L : 6000,
                        false,
                        video ? frameCount : 0,
                        video ? VideoFrameSampling.plan(frameCount, 80) : null));
    }

    PreparedMedia.Snapshot toleratedSnapshot() {
        PreparationContracts.Manifest base = snapshot(true, 10).media().preparation();
        List<PreparationContracts.Item> items = base.items()
                .stream()
                .filter(i -> !"FRAME".equals(i.role()) || i.index() != 2 && i.index() != 7)
                .toList();
        var manifest = new Manifest(
                base.preparationId(),
                base.draftId(),
                base.mediaVersion(),
                base.userId(),
                base.parameterVersion(),
                items, base.durationMs(), base.hasAudio(), 8, base.videoSampling());
        return PreparedMedia.freeze(new Request(101L, 1L, 2, 9L,
                new ContentMedia("video", new VideoMedia("uploaded-video", 0), null), null), manifest);
    }

    MediaTaskMessage toleratedMessage() {
        MediaTaskMessage message = message(true);
        PreparedMedia.Snapshot snapshot = toleratedSnapshot();
        message.setInput(new PipelineContracts.Input(
                100L,
                1L,
                9L,
                "title",
                "description",
                "",
                null,
                snapshot.media(),
                1));
        return message;
    }

    MediaTaskMessage message(boolean video) {
        return message(video, 8);
    }

    MediaTaskMessage message(boolean video, int count) {
        PreparedMedia.Snapshot snapshot = snapshot(video, count);
        return MediaTaskMessage.builder().pipelineId(100L).ownerId(1L).taskId(7L).userId(9L).attempt(1)
                .objectKey(video ? snapshot.media().video().objectKey() : null)
                .input(new PipelineContracts.Input(
                        100L,
                        1L,
                        9L,
                        "title",
                        "description",
                        "",
                        snapshot.coverObjectKey(),
                        snapshot.media(),
                        1))
                .dependencies(Map.of()).build();
    }

    @BeforeEach
    void setup() {
        when(storage.signGetUrl(anyString(), anyLong())).thenAnswer(call -> "signed:" + call.getArgument(0));
        when(ocr.recognize(any())).thenAnswer(call -> {
            OcrPort.Request request = call.getArgument(0);
            return new OcrPort.Response(request.urls().stream().map(url -> new OcrPort.Item(url, "text", null)).toList());
        });
        when(artifacts.write(anyLong(), eq("OCR"), anyInt(), any())).thenReturn("ocr-artifact");
    }

    ContentArtifacts.Ocr recognize(boolean video) throws Exception {
        new OcrTaskExecutor(
                ocr,
                guard,
                storage,
                artifacts,
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings())
                .execute(message(video));
        ArgumentCaptor<ContentArtifacts.Ocr> captured = org.mockito.ArgumentCaptor.forClass(ContentArtifacts.Ocr.class);
        verify(artifacts).write(eq(100L), eq("OCR"), eq(1), captured.capture());
        return captured.getValue();
    }

    @Test
    void dynamicOcrUsesOnlySmallImagesAndPreservesOriginalMapping() throws Exception {
        ContentArtifacts.Ocr result = recognize(false);
        assertEquals(2, result.frames().size());
        ContentArtifacts.Frame second = result.frames().get(1);
        assertEquals(prefix + "image-1", second.objectKey());
        assertEquals(prefix + "ocr-1", second.derivedObjectKey());
        assertEquals(3000, second.timeMs());
        assertEquals(1, second.imageIndex());
        verify(storage, never()).signGetUrl(contains("image-"), anyLong());
        PreparedMedia.validateOcr(snapshot(false).media(), result);
    }

    @Test
    void videoOcrUsesAllEightPreparedFramesWithoutStorageWritesOrVideoAccess() throws Exception {
        ContentArtifacts.Ocr result = recognize(true);
        assertEquals(8, result.frames().size());
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

    @Test
    void toleratedOcrPreservesSparseSlotsAndRequiresEveryFrozenResultAfterConfigChange() throws Exception {
        var settings = new FrameProcessingSettings(new org.springframework.mock.env.MockEnvironment()
                .withProperty("my-youtube.frame-processing.min-success-percent", "100"));
        new OcrTaskExecutor(ocr, guard, storage, artifacts, settings).execute(toleratedMessage());
        ArgumentCaptor<ContentArtifacts.Ocr> captured = org.mockito.ArgumentCaptor.forClass(ContentArtifacts.Ocr.class);
        verify(artifacts).write(eq(100L), eq("OCR"), eq(1), captured.capture());
        ContentArtifacts.Ocr result = captured.getValue();
        assertEquals(
                List.of(0, 1, 3, 4, 5, 6, 8, 9),
                result.frames().stream().map(ContentArtifacts.Frame::imageIndex).toList());
        PreparedMedia.validateOcr(toleratedSnapshot().media(), result);
        assertThrows(IllegalArgumentException.class, () -> PreparedMedia.validateOcr(toleratedSnapshot().media(),
                new ContentArtifacts.Ocr(result.frames().subList(0, 7))));
        verify(ocr, times(4)).recognize(any());
    }

    @Test
    void toleratedInputDoesNotPermitPartialOcrBatchSuccess() {
        doAnswer(call -> {
            OcrPort.Request request = call.getArgument(0);
            return new OcrPort.Response(List.of(new OcrPort.Item(request.urls().get(0), "text", null)));
        }).when(ocr).recognize(any());
        assertThrows(IllegalStateException.class, () -> new OcrTaskExecutor(ocr, guard, storage, artifacts,
                new FrameProcessingSettings()).execute(toleratedMessage()));
        verifyNoInteractions(artifacts);
    }

    @Test
    void removingOneFrozenFrameCannotBeHiddenByLoweringActualCount() {
        ContentMedia media = toleratedSnapshot().media();
        PreparationContracts.Manifest valid = media.preparation();
        var manifest = new Manifest(
                valid.preparationId(),
                valid.draftId(),
                valid.mediaVersion(),
                valid.userId(),
                valid.parameterVersion(),
                valid.items()
                        .subList(0, valid.items().size() - 1),
                        valid.durationMs(),
                        valid.hasAudio(),
                        7,
                        valid.videoSampling());
        assertThrows(IllegalArgumentException.class, () -> PreparedMedia.freeze(new Request(101L, 1L, 2, 9L,
                new ContentMedia("video", new VideoMedia("uploaded-video", 0), null), null), manifest));
    }

    @Test
    void silentVideoCreatesExplicitEmptyAsrWithoutCallingModel() {
        AsrPort asr = mock(AsrPort.class);
        when(artifacts.write(anyLong(), eq("ASR"), anyInt(), any())).thenReturn("empty-segments");
        JsonObject result = StructuredJson.object(new AsrTaskExecutor(asr, guard, storage, artifacts).execute(message(true)));
        assertTrue(result.get("noAudio").getAsBoolean());
        assertTrue(result.get("speechEmpty").getAsBoolean());
        verifyNoInteractions(asr);
        verify(storage, never()).signGetUrl(anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 24, 64})
    void adaptiveOcrConsumesEveryDeclaredFrameInBoundedBatches(int count) throws Exception {
        new OcrTaskExecutor(
                ocr,
                guard,
                storage,
                artifacts,
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings())
                .execute(message(
                true,
                count));
        ArgumentCaptor<ContentArtifacts.Ocr> result = org.mockito.ArgumentCaptor.forClass(ContentArtifacts.Ocr.class);
        verify(artifacts).write(eq(100L), eq("OCR"), eq(1), result.capture());
        assertEquals(count, result.getValue().frames().size());
        PreparedMedia.validateOcr(snapshot(true, count).media(), result.getValue());
        verify(ocr, times((count + 1) / 2)).recognize(any());
        ArgumentCaptor<OcrPort.Request> requests = org.mockito.ArgumentCaptor.forClass(OcrPort.Request.class);
        verify(ocr, atLeastOnce()).recognize(requests.capture());
        assertTrue(requests.getAllValues().stream().allMatch(r -> r.urls().size() <= 2));
        assertEquals(java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "signed:" + prefix + "frame-" + i)
                .toList(),
                requests.getAllValues().stream().flatMap(r -> r.urls().stream()).toList());
    }

    @Test
    void missingLastDeclaredFrameCannotFreezeOrBecomeReady() {
        ContentMedia media = snapshot(true, 64).media();
        PreparationContracts.Manifest valid = media.preparation();
        var incomplete = new Manifest(valid.preparationId(), valid.draftId(), valid.mediaVersion(), valid.userId(),
                valid.parameterVersion(), valid.items()
                        .subList(0, valid.items().size() - 1),
                        valid.durationMs(),
                        valid.hasAudio(),
                        64,
                        valid.videoSampling());
        assertThrows(RuntimeException.class, () -> PreparedMedia.freeze(new Request(101L, 1L, 2, 9L,
                new ContentMedia("video", new VideoMedia("uploaded-video", 0), null), null), incomplete));
    }

    @Test
    void ocrHotUpdateAppliesToNextAttemptWithoutChangingRunningBatchSize() throws Exception {
        var settings = new FrameProcessingSettings();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            OcrPort.Request request = call.getArgument(0);
            if (calls.getAndIncrement() == 0) settings.replace(
                    "interval-seconds: 5\nmax-frames: 10\nmin-success-percent: 80\ncover-max-candidates: 8\nocr-batch-size: 1\nreview-batch-size: 4");
            return new OcrPort.Response(request.urls().stream().map(url -> new OcrPort.Item(url, "text", null)).toList());
        }).when(ocr).recognize(any());
        var executor = new OcrTaskExecutor(ocr, guard, storage, artifacts, settings);
        executor.execute(message(true, 6));
        executor.execute(message(true, 6));
        ArgumentCaptor<OcrPort.Request> requests = org.mockito.ArgumentCaptor.forClass(OcrPort.Request.class);
        verify(ocr, times(9)).recognize(requests.capture());
        assertEquals(List.of(2, 2, 2, 1, 1, 1, 1, 1, 1),
                requests.getAllValues().stream().map(r -> r.urls().size()).toList());
    }

    @Test
    void preparedCoverReferencesSelectedFrameAndDynamicSelectedOriginal() {
        VideoCoverGenerator generator = mock(VideoCoverGenerator.class);
        when(generator.selectPrepared(any())).thenReturn(prefix + "frame-3");
        var executor = new CoverTaskExecutor(generator);
        assertEquals(
                prefix + "frame-3",
                StructuredJson.object(executor.execute(message(true))).get("coverObjectKey").getAsString());
        assertEquals(
                prefix + "image-1",
                StructuredJson.object(executor.execute(message(false))).get("coverObjectKey").getAsString());
        verify(generator, never()).generate(anyLong(), anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparedCoverScoresBoundedPreviewsAndReturnsOriginalKeyWithoutUpload(boolean tolerated) throws Exception {
        PreparationStorage preparedStorage = mock(PreparationStorage.class);
        when(preparedStorage.inspect(anyString())).thenAnswer(call -> new PreparationStorage.Source(
                call.getArgument(0),
                null,
                "etag",
                100));
        doAnswer(call -> {
            Path file = call.getArgument(1);
            var image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(java.awt.Color.GRAY);
            graphics.fillRect(0, 0, 1600, 900);
            graphics.dispose();
            javax.imageio.ImageIO.write(image, "jpg", file.toFile());
            image.flush();
            return null;
        }).when(preparedStorage).download(any(), any(), anyLong());
        CoverVisionSelector selector = mock(CoverVisionSelector.class);
        when(selector.pickBest(anyList())).thenAnswer(call -> {
            List<cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil.FrameScore> candidates = call.getArgument(0);
            assertEquals(3, candidates.size());
            for (FFmpegUtil.FrameScore candidate : candidates) {
                BufferedImage image = javax.imageio.ImageIO.read(candidate.framePath().toFile());
                assertTrue(Math.max(image.getWidth(), image.getHeight()) <= 512);
                image.flush();
            }
            return 2;
        });
        var settings = new FrameProcessingSettings(new org.springframework.mock.env.MockEnvironment()
                .withProperty("my-youtube.frame-processing.cover-max-candidates", "3"));
        var generator = new VideoCoverGenerator(storage, selector, preparedStorage, settings);
        assertEquals(prefix + "frame-" + (tolerated ? 3 : 2), generator.selectPrepared(
                (tolerated ? toleratedSnapshot() : snapshot(true)).media().preparation()));
        verifyNoInteractions(storage);
        verify(preparedStorage, times(8)).download(any(), any(), anyLong());
        verify(preparedStorage, never()).upload(any(), anyString());
    }
}
