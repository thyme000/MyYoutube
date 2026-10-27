package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import cn.diaojiahong.myyoutube.transcode.service.*;
import cn.diaojiahong.myyoutube.transcode.task.PrepareTaskExecutor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(MediaTaskIntegrationTest.Config.class)
class PreparationWorkflowTest {
    @Autowired MediaPreparationMapper preparations;
    @Autowired PreparationItemMapper items;
    @Autowired MediaTaskMapper taskRows;
    @Autowired PipelineMapper pipelines;
    @Autowired cn.diaojiahong.myyoutube.video.api.ContentPipelinePort contentVideo;
    @Autowired MediaTaskService tasks;
    @Autowired MediaTaskDispatcher dispatcher;
    @Autowired TransactionTemplate transaction;
    @Autowired DataSource dataSource;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    PreparationRegistry registry;
    MediaPreparationProperties properties;
    FrameProcessingSettings frameSettings;
    JdbcTemplate jdbc;
    PreparationStorage storage;
    PreparedImageWriter images;
    PreparedVideoReader videos;
    S3StorageUtil urls;
    PrepareTaskExecutor executor;
    Map<String, PreparationStorage.Source> objects;
    io.micrometer.core.instrument.simple.SimpleMeterRegistry samplingMetrics;

    Request request() {
        var media = new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                List.of(new DynamicMedia.Image("upload/a", 0, 3000, .5, .5),
                        new DynamicMedia.Image("upload/b", 3000, 3000, .5, .5)), null, 6000);
        return new Request(101L, 1L, 0, 9L, new ContentMedia("dynamic", null, media), "upload/a");
    }
    @BeforeEach void setup() throws Exception {
        reset(dispatcher);
        jdbc = new JdbcTemplate(dataSource);
        observations.getRecording().setEnabled(false);
        OutboxTestDatabase.reset(jdbc);
        for (var table : List.of("media_preparation_item", "media_preparation", "media_task", "media_pipeline"))
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(dataSource);
        properties = new MediaPreparationProperties();
        frameSettings = new FrameProcessingSettings();
        registry = new PreparationRegistry(preparations, items, taskRows, tasks, transaction, properties, frameSettings);
        storage = mock(PreparationStorage.class); images = mock(PreparedImageWriter.class);
        videos = mock(PreparedVideoReader.class); urls = mock(S3StorageUtil.class);
        samplingMetrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        executor = new PrepareTaskExecutor(registry, storage, images, videos, properties, urls, frameSettings, samplingMetrics);
        objects = new HashMap<>();
        for (var key : List.of("upload/a", "upload/b", "upload/video"))
            objects.put(key, new PreparationStorage.Source(key, "v1", "source-etag", 10));
        when(storage.inspect(anyString())).thenAnswer(call -> Objects.requireNonNull(objects.get(call.getArgument(0))));
        when(storage.inspectIfPresent(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(storage.freeze(any(), anyString())).thenAnswer(call -> {
            PreparationStorage.Source source = call.getArgument(0);
            assertEquals("v1", source.versionId());
            String key = call.getArgument(1);
            var frozen = new PreparationStorage.Source(key, "frozen-v1", "frozen-etag", source.bytes());
            objects.put(key, frozen); return frozen;
        });
        doAnswer(call -> { Files.write(call.getArgument(1), new byte[10]); return null; })
                .when(storage).download(any(), any(), anyLong());
        when(images.write(any(), any())).thenAnswer(call -> {
            Files.write(call.getArgument(1), new byte[4]);
            return new PreparedImageWriter.Result(100, 100, 80, 80, 1, 4);
        });
        doAnswer(call -> {
            String key = call.getArgument(1);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_preparation_item WHERE object_key=? AND status='PLANNED'", Integer.class, key));
            objects.put(key, new PreparationStorage.Source(key, null, "output-etag", Files.size(call.getArgument(0))));
            return null;
        }).when(storage).upload(any(), anyString());
    }
    MediaTaskMessage claim() {
        var row = tasks.listByDraft(101L).get(0);
        return tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).phase("DRAFT").attempt(row.getAttempt()).build());
    }
    java.time.LocalDateTime past(int seconds) { return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(seconds); }
    void due() {
        jdbc.update("UPDATE media_task SET next_retry_at=? WHERE status='RETRY_WAIT'", past(1));
        tasks.recover();
    }
    Manifest succeed(MediaTaskMessage execution) throws Exception {
        var result = executor.execute(execution);
        tasks.completeAndAdvance(execution, result, null);
        return registry.view(101L).manifest();
    }
    @Test void registrationIsIdempotentBoundedAndDoesNoMediaIo() {
        observations.getRecording().setEnabled(true);
        registry.start(request()); registry.start(request());
        assertEquals(1, tasks.listByDraft(101L).size());
        assertEquals("PREPARE", tasks.listByDraft(101L).get(0).getTaskType());
        assertEquals(MediaTaskStatus.READY, registry.view(101L).status());
        var event = StructuredJson.object(jdbc.queryForObject("SELECT payload FROM pipeline_event_outbox", String.class));
        assertEquals("1", event.get("draftId").getAsString());
        assertEquals("101", event.get("preparationId").getAsString());
        assertEquals("0", event.get("mediaVersion").getAsString());
        verify(dispatcher, times(1)).dispatch(any()); verifyNoInteractions(storage, images, videos, urls);
        var changed = new Request(101L, 1L, 1, 9L, request().media(), "upload/a");
        assertThrows(IllegalStateException.class, () -> registry.start(changed));
        assertThrows(IllegalArgumentException.class, () -> registry.start(new Request(102L, 1L, -1, 9L, request().media(), "upload/a")));
    }
    @Test void concurrentRegistrationHasOneTaskAndOneDispatch() {
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> registry.start(request())),
                CompletableFuture.runAsync(() -> registry.start(request()))).join();
        assertEquals(1, tasks.listByDraft(101L).size()); verify(dispatcher, times(1)).dispatch(any());
    }
    @Test void failedTaskRegistrationRollsBackManifestAndDoesNotDispatch() {
        jdbc.execute("ALTER TABLE media_task ADD CONSTRAINT reject_prepare CHECK (task_type <> 'PREPARE')");
        assertThrows(RuntimeException.class, () -> registry.start(request()));
        assertNull(preparations.selectById(101L)); verifyNoInteractions(dispatcher);
    }
    @Test void partialImageFailureReusesSuccessfulObjectsAndKeepsOrderedOriginalMapping() throws Exception {
        registry.start(request()); var first = claim();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("decoder unavailable");
            Files.write(call.getArgument(1), new byte[4]);
            return new PreparedImageWriter.Result(100, 100, 80, 80, 1, 4);
        }).when(images).write(any(), any());
        var error = assertThrows(IllegalStateException.class, () -> executor.execute(first));
        tasks.completeAndAdvance(first, null, error.getMessage());
        assertEquals("READY", registry.find(101L, "OCR_IMAGE", 0).getStatus());
        assertEquals(MediaTaskStatus.RETRY_WAIT, registry.view(101L).status());
        due(); var second = claim(); var manifest = succeed(second);
        assertEquals(2, second.getAttempt()); assertEquals(3, calls.get());
        assertEquals(4, manifest.items().size()); assertEquals(6000, manifest.durationMs());
        assertEquals(0, manifest.items().get(1).index()); assertEquals(3000, manifest.items().get(3).timeMs());
        assertEquals(manifest.items().get(2).objectKey(), manifest.items().get(3).originalObjectKey());
        assertFalse(manifest.hasAudio()); verify(storage, times(2)).upload(any(), anyString());
        assertTrue(tasks.listByDraft(101L).get(0).getCompletionAck());
    }
    @Test void uploadedButUnconfirmedObjectIsRecoveredWithoutSecondUpload() throws Exception {
        registry.start(request()); var old = claim();
        var lost = new AtomicBoolean();
        doAnswer(call -> {
            String key = call.getArgument(1);
            objects.put(key, new PreparationStorage.Source(key, null, "output-etag", 4));
            if (lost.compareAndSet(false, true)) {
                jdbc.update("UPDATE media_task SET update_time=?", past(600));
                tasks.recover();
            }
            return null;
        }).when(storage).upload(any(), anyString());
        assertThrows(IllegalStateException.class, () -> executor.execute(old));
        assertEquals("PLANNED", registry.find(101L, "OCR_IMAGE", 0).getStatus());
        assertThrows(IllegalStateException.class, () -> registry.reserve(old, "FRAME", 0, null));
        tasks.completeAndAdvance(old, "{}", null);
        assertEquals(MediaTaskStatus.RETRY_WAIT, registry.view(101L).status());
        due(); assertNotNull(succeed(claim()));
        verify(storage, times(2)).upload(any(), anyString());
    }
    @Test void exhaustedRetriesRequireExplicitRetryAndKeepAttemptMonotonic() {
        registry.start(request());
        for (int i = 0; i < 4; i++) {
            var execution = claim(); tasks.completeAndAdvance(execution, null, "storage unavailable");
            if (i < 3) due();
        }
        assertEquals(MediaTaskStatus.DEAD, registry.view(101L).status()); assertEquals(4, registry.view(101L).attempt());
        assertTrue(tasks.retryPreparation(101L)); assertTrue(tasks.retryPreparation(101L));
        assertEquals(5, claim().getAttempt()); assertEquals(0, registry.view(101L).retryCount());
    }
    @Test void configurationFingerprintIsOrderIndependentAndChangesRejectExecution() {
        var before = registry.parameterVersion();
        var reversed = new ArrayList<>(properties.getVideoBrands()); Collections.reverse(reversed);
        properties.setVideoBrands(new LinkedHashSet<>(reversed)); assertEquals(before, registry.parameterVersion());
        registry.start(request()); assertFalse(registry.view(101L).parametersChanged());
        properties.setLongEdge(1280); assertTrue(registry.view(101L).parametersChanged());
        var error = assertThrows(IllegalStateException.class, () -> executor.execute(claim()));
        assertEquals(PreparationContracts.PARAMETERS_CHANGED_MESSAGE, error.getMessage()); verifyNoInteractions(storage);
    }
    @Test void failedOldConfigurationIsReportedWithoutDependingOnStoredErrorText() {
        properties.setVideoMaxLevel(42); registry.start(request());
        for (int i = 0; i < 4; i++) {
            tasks.completeAndAdvance(claim(), null, "Preparation parameters changed; create a new media version");
            if (i < 3) due();
        }
        properties.setVideoMaxLevel(51);
        var view = registry.view(101L);
        assertEquals(MediaTaskStatus.DEAD, view.status()); assertTrue(view.parametersChanged());
        assertEquals(PreparationContracts.PARAMETERS_CHANGED_MESSAGE, view.errorMessage());
        verifyNoInteractions(storage, images, videos, urls);
    }
    @Test void successfulManifestIsPreservedAfterConfigurationChange() throws Exception {
        finishPreparation();
        var manifest = registry.view(101L).manifest(); properties.setVideoMaxLevel(42);
        var view = registry.view(101L);
        assertTrue(view.parametersChanged()); assertEquals(MediaTaskStatus.SUCCESS, view.status());
        assertEquals(manifest, view.manifest()); assertNull(view.errorMessage());
    }
    @Test void videoPartialFailureRecoversPlannedFramesWithoutTranscodeOrRepeatedSuccessfulFrames() throws Exception {
        var request = new Request(101L, 1L, 0, 9L, new ContentMedia("video", new VideoMedia("upload/video", 0), null), null);
        registry.start(request);
        when(urls.signGetUrl(anyString(), anyLong())).thenReturn("protected-source");
        when(urls.readHeadBytes(anyString(), anyInt())).thenReturn(new byte[10]);
        var probe = new PreparedVideoReader.Probe(30000, 320, 180, 0, false, "High", 42, 30);
        when(videos.probe(anyString(), any())).thenReturn(probe);
        var failed = new AtomicBoolean(); var decoded = new ArrayList<Integer>();
        doAnswer(call -> {
            Path work = call.getArgument(2); java.util.function.IntPredicate skip = call.getArgument(4);
            PreparedVideoReader.FrameConsumer consumer = call.getArgument(5);
            for (int i = 0; i < probe.sampleCount(); i++) {
                if (skip.test(i)) continue;
                if (i == 2 && failed.compareAndSet(false, true)) throw new IllegalStateException("frame unavailable");
                decoded.add(i); Path frame = work.resolve("frame.jpg"); Files.write(frame, new byte[4]);
                consumer.accept(new PreparedVideoReader.Frame(i, 10 + 100L * i, frame,
                        new PreparedImageWriter.Result(320, 180, 320, 180, 1, 4)));
            }
            return null;
        }).when(videos).frames(anyString(), any(), any(), any(VideoFrameSampling.Plan.class), any(), any());
        var first = claim(); assertThrows(IllegalStateException.class, () -> executor.execute(first));
        tasks.completeAndAdvance(first, null, "frame unavailable"); due();
        var manifest = succeed(claim()); assertEquals(7, manifest.items().size());
        assertEquals(6, manifest.videoFrameCount());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), decoded);
        assertEquals(510, manifest.items().get(6).timeMs()); verify(storage, times(6)).upload(any(), anyString());
        verifyNoInteractions(images); assertFalse(manifest.hasAudio());
    }
    @Test void onlySamplingUpdatesChangePreparationFingerprint() {
        String original = registry.parameterVersion();
        frameSettings.replace("interval-seconds: 5\nmax-frames: 10\nmin-success-percent: 80\ncover-max-candidates: 3\nocr-batch-size: 1\nreview-batch-size: 2");
        assertEquals(original, registry.parameterVersion());
        frameSettings.replace("interval-seconds: 10\nmax-frames: 3\nmin-success-percent: 80\ncover-max-candidates: 3\nocr-batch-size: 1\nreview-batch-size: 2");
        assertNotEquals(original, registry.parameterVersion());
    }

    void mockVideoSamples(Set<Integer> missing, List<Integer> decoded) throws Exception {
        registry.start(new Request(101L, 1L, 0, 9L, new ContentMedia("video", new VideoMedia("upload/video", 0), null), null));
        when(urls.signGetUrl(anyString(), anyLong())).thenReturn("protected-source");
        when(urls.readHeadBytes(anyString(), anyInt())).thenReturn(new byte[10]);
        when(videos.probe(anyString(), any())).thenReturn(new PreparedVideoReader.Probe(50000, 320, 180, 0, false, "High", 42, 30));
        doAnswer(call -> {
            Path work = call.getArgument(2); VideoFrameSampling.Plan plan = call.getArgument(3);
            java.util.function.IntPredicate skip = call.getArgument(4);
            PreparedVideoReader.FrameConsumer consumer = call.getArgument(5);
            for (int i = 0; i < plan.targetCount(); i++) {
                if (skip.test(i) || missing.contains(i)) continue;
                decoded.add(i);
                Path frame = work.resolve("frame.jpg"); Files.write(frame, new byte[4]);
                consumer.accept(new PreparedVideoReader.Frame(i, i * 5000L, frame,
                        new PreparedImageWriter.Result(320, 180, 320, 180, 1, 4)));
            }
            return null;
        }).when(videos).frames(anyString(), any(), any(), any(VideoFrameSampling.Plan.class), any(), any());
    }

    @Test void toleratedVideoFreezesActualSlotsAndSurvivesStricterConfiguration() throws Exception {
        mockVideoSamples(Set.of(2, 7), new ArrayList<>());
        var manifest = succeed(claim());
        assertEquals(10, manifest.videoSampling().targetCount());
        assertEquals(8, manifest.videoSampling().minimumCount());
        assertEquals(8, manifest.videoFrameCount());
        assertEquals(List.of(0, 1, 3, 4, 5, 6, 8, 9), PreparedMedia.videoFrames(manifest).stream().map(Item::index).toList());
        frameSettings.replace("interval-seconds: 5\nmax-frames: 10\nmin-success-percent: 100\ncover-max-candidates: 8\nocr-batch-size: 2\nreview-batch-size: 4");
        assertTrue(registry.view(101L).parametersChanged());
        assertEquals(manifest, registry.view(101L).manifest());
        assertNotNull(PreparedMedia.freeze(new Request(101L, 1L, 0, 9L,
                new ContentMedia("video", new VideoMedia("upload/video", 0), null), null), manifest));
        assertEquals(1, samplingMetrics.get(MetricNames.MEDIA_VIDEO_SAMPLING_ATTEMPTS).tag("outcome", "TOLERATED").counter().count());
        verify(storage, times(8)).upload(any(), anyString());
    }

    @Test void insufficientVideoRetriesOnlyMissingSlotsAndKeepsOriginalIndices() throws Exception {
        var missing = new HashSet<>(Set.of(2, 5, 7)); var decoded = new ArrayList<Integer>();
        mockVideoSamples(missing, decoded);
        var first = claim();
        var error = assertThrows(VideoFrameSampling.Rejected.class, () -> executor.execute(first));
        assertEquals("INSUFFICIENT_FRAMES", error.reason());
        assertNull(registry.view(101L).manifest());
        tasks.completeAndAdvance(first, null, error.getMessage());
        assertEquals(MediaTaskStatus.RETRY_WAIT, registry.view(101L).status());
        missing.remove(5); due();
        var manifest = succeed(claim());
        assertEquals(List.of(0, 1, 3, 4, 6, 8, 9, 5), decoded);
        assertEquals(List.of(0, 1, 3, 4, 5, 6, 8, 9), PreparedMedia.videoFrames(manifest).stream().map(Item::index).toList());
        verify(storage, times(8)).upload(any(), anyString());
        assertEquals(1, samplingMetrics.get(MetricNames.MEDIA_VIDEO_SAMPLING_ATTEMPTS).tag("outcome", "INSUFFICIENT_FRAMES").counter().count());
        assertEquals(2, registry.view(101L).attempt());
    }

    @Test void coverageFailureCannotFreezeEightFramesAndCanRecoverWithoutReupload() throws Exception {
        var missing = new HashSet<>(Set.of(2, 3)); var decoded = new ArrayList<Integer>();
        mockVideoSamples(missing, decoded);
        var first = claim();
        var error = assertThrows(VideoFrameSampling.Rejected.class, () -> executor.execute(first));
        assertEquals("COVERAGE_GAP", error.reason());
        tasks.completeAndAdvance(first, null, error.getMessage());
        missing.remove(2); due();
        var manifest = succeed(claim());
        assertEquals(9, manifest.videoFrameCount());
        assertEquals(2, decoded.get(decoded.size() - 1));
        verify(storage, times(9)).upload(any(), anyString());
        assertEquals(1, samplingMetrics.get(MetricNames.MEDIA_VIDEO_SAMPLING_ATTEMPTS).tag("outcome", "COVERAGE_GAP").counter().count());
    }

    @Test void successRatioChangesPreparationFingerprintEvenWhenTargetIsUnchanged() {
        String before = registry.parameterVersion();
        frameSettings.replace("interval-seconds: 5\nmax-frames: 10\nmin-success-percent: 90\ncover-max-candidates: 8\nocr-batch-size: 2\nreview-batch-size: 4");
        assertNotEquals(before, registry.parameterVersion());
    }

    @Test void videoUploadFailureCannotFreezeEvenAfterMinimumFramesWereUploaded() throws Exception {
        mockVideoSamples(Set.of(2), new ArrayList<>());
        doAnswer(call -> {
            String key = call.getArgument(1);
            if (jdbc.queryForObject("SELECT item_index FROM media_preparation_item WHERE object_key=?", Integer.class, key) == 9)
                throw new IllegalStateException("storage unavailable");
            objects.put(key, new PreparationStorage.Source(key, null, "output-etag", Files.size(call.getArgument(0))));
            return null;
        }).when(storage).upload(any(), anyString());
        var task = claim();
        var error = assertThrows(IllegalStateException.class, () -> executor.execute(task));
        assertEquals("storage unavailable", error.getMessage());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM media_preparation_item WHERE role='FRAME' AND status='READY'", Integer.class));
        assertNull(registry.view(101L).manifest());
        tasks.completeAndAdvance(task, null, error.getMessage());
        assertEquals(MediaTaskStatus.RETRY_WAIT, registry.view(101L).status());
    }

    @Test void retirementReclaimsPlannedObjectOmittedFromToleratedManifest() throws Exception {
        mockVideoSamples(Set.of(2, 7), new ArrayList<>());
        var task = claim();
        var pending = registry.reserve(task, "FRAME", 2, null);
        registry.planned(task, pending, "{\"bytes\":4,\"timeMs\":10000,\"width\":320,\"height\":180}");
        objects.put(pending.getObjectKey(), new PreparationStorage.Source(pending.getObjectKey(), null, "late-upload", 4));
        var manifest = succeed(task);
        assertEquals(8, manifest.videoFrameCount());
        assertFalse(manifest.items().stream().anyMatch(item -> item.objectKey().equals(pending.getObjectKey())));
        var cleaner = reclaimer(released()); cleaner.reclaim();
        reclaimDue(true); cleaner.reclaim();
        verify(storage).delete(pending.getObjectKey());
        assertEquals("DELETED", registry.find(101L, "FRAME", 2).getStatus());
    }
    @Test void runningVideoAttemptRetainsCapturedPlanDuringHotUpdate() throws Exception {
        registry.start(new Request(101L, 1L, 0, 9L, new ContentMedia("video", new VideoMedia("upload/video", 0), null), null));
        when(urls.signGetUrl(anyString(), anyLong())).thenReturn("protected-source");
        when(urls.readHeadBytes(anyString(), anyInt())).thenReturn(new byte[10]);
        when(videos.probe(anyString(), any())).thenAnswer(call -> {
            frameSettings.replace("interval-seconds: 10\nmax-frames: 2\nmin-success-percent: 80\ncover-max-candidates: 3\nocr-batch-size: 1\nreview-batch-size: 2");
            return new PreparedVideoReader.Probe(120000, 320, 180, 0, false, "High", 42, 30);
        });
        doAnswer(call -> {
            assertEquals(10, ((VideoFrameSampling.Plan) call.getArgument(3)).targetCount());
            Path work = call.getArgument(2);
            PreparedVideoReader.FrameConsumer consumer = call.getArgument(5);
            for (int i = 0; i < 10; i++) {
                Path frame = work.resolve("frame.jpg"); Files.write(frame, new byte[4]);
                consumer.accept(new PreparedVideoReader.Frame(i, i * 100L, frame,
                        new PreparedImageWriter.Result(320, 180, 320, 180, 1, 4)));
            }
            return null;
        }).when(videos).frames(anyString(), any(), any(), any(VideoFrameSampling.Plan.class), any(), any());
        var manifest = succeed(claim());
        assertEquals(10, manifest.videoFrameCount());
        assertEquals(2, frameSettings.snapshot().maxFrames());
        assertTrue(registry.view(101L).parametersChanged());
        assertEquals(MediaTaskStatus.SUCCESS, registry.view(101L).status());
    }
    @Test void changedPreparedObjectCannotBecomeSuccessfulManifest() throws Exception {
        registry.start(request()); var first = claim();
        var result = executor.execute(first);
        var image = registry.find(101L, "OCR_IMAGE", 0);
        objects.put(image.getObjectKey(), new PreparationStorage.Source(image.getObjectKey(), null, "changed", 9));
        assertThrows(IllegalStateException.class, () -> executor.execute(first));
        assertNull(registry.view(101L).manifest());
        assertEquals(MediaTaskStatus.RUNNING, registry.view(101L).status());
        assertNotNull(result);
    }
    @Test void missingPreparedImageIsRebuiltFromFrozenOriginal() throws Exception {
        registry.start(request()); var first = claim(); executor.execute(first);
        var image = registry.find(101L, "OCR_IMAGE", 0);
        objects.remove(image.getObjectKey()); objects.remove("upload/a"); objects.remove("upload/b");
        var manifest = succeed(first); assertEquals(4, manifest.items().size());
        verify(storage, times(3)).upload(any(), anyString());
        verify(storage, times(2)).freeze(any(), anyString());
    }
    @Test void audioIsFrozenIndependentlyAndDoesNotChangeImageTiming() throws Exception {
        var dynamic = request().media().dynamic();
        objects.put("upload/audio", new PreparationStorage.Source("upload/audio", "v1", "etag", 10));
        var withAudio = new DynamicMedia(dynamic.canvasType(), dynamic.canvasW(), dynamic.canvasH(), dynamic.transition(),
                dynamic.fadeMs(), dynamic.items(), new DynamicMedia.Audio(7L, "upload/audio", "music", 20000), dynamic.exportDurationMs());
        registry.start(new Request(101L, 1L, 0, 9L, new ContentMedia("dynamic", null, withAudio), "upload/a"));
        var manifest = succeed(claim()); assertTrue(manifest.hasAudio()); assertEquals(6000, manifest.durationMs());
        assertEquals("AUDIO", manifest.items().get(4).role());
        assertTrue(manifest.items().get(4).objectKey().startsWith("media-prepared/"));
        assertEquals(3000, manifest.items().get(3).timeMs());
    }
    PreparationReclaimer reclaimer(cn.diaojiahong.myyoutube.video.api.MediaDraftPort video) {
        return new PreparationReclaimer(preparations, items, taskRows, pipelines,
                new PreparationLifecycle(preparations), video, storage, transaction, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
    cn.diaojiahong.myyoutube.video.api.MediaDraftPort released() {
        var video = mock(cn.diaojiahong.myyoutube.video.api.MediaDraftPort.class);
        when(video.preparationReference(any())).thenReturn("RELEASED");
        return video;
    }
    void reclaimDue(boolean graceElapsed) {
        jdbc.update("UPDATE media_preparation SET reclaim_after=?", past(1));
        if (graceElapsed) jdbc.update("UPDATE media_preparation SET retired_at=?", past(172800));
    }
    void finishPreparation() throws Exception {
        registry.start(request());
        var execution = claim();
        tasks.completeAndAdvance(execution, executor.execute(execution), null);
    }
    @Test void retainedUnknownAndUnavailableReferencesNeverDelete() throws Exception {
        finishPreparation();
        var video = released(); var cleaner = reclaimer(video);
        for (var reference : List.of("RETAINED", "UNKNOWN", "unexpected")) {
            when(video.preparationReference(any())).thenReturn(reference);
            reclaimDue(false); cleaner.reclaim();
            assertEquals("ACTIVE", registry.get(101L).getLifecycle());
        }
        when(video.preparationReference(any())).thenThrow(new IllegalStateException("video unavailable"));
        reclaimDue(false); cleaner.reclaim();
        verify(storage, never()).delete(anyString());
        assertEquals("ACTIVE", registry.get(101L).getLifecycle());
    }
    @Test void activePrepareAndReviewConsumersPreventRetirement() throws Exception {
        registry.start(request()); var cleaner = reclaimer(released());
        cleaner.reclaim(); assertEquals("ACTIVE", registry.get(101L).getLifecycle());
        var execution = claim(); reclaimDue(false); cleaner.reclaim();
        assertEquals("ACTIVE", registry.get(101L).getLifecycle());
        tasks.completeAndAdvance(execution, executor.execute(execution), null);
        jdbc.update("INSERT INTO media_task(id,owner_id,pipeline_id,phase,task_type,status,dependencies_json) VALUES(500,1,NULL,'REVIEW','REVIEW','RETRY_WAIT','{}')");
        reclaimDue(false); cleaner.reclaim(); assertEquals("ACTIVE", registry.get(101L).getLifecycle());
        jdbc.update("UPDATE media_task SET status='DEAD' WHERE id=500");
        reclaimDue(false); cleaner.reclaim(); assertEquals("RETIRED", registry.get(101L).getLifecycle());
        verify(storage, never()).delete(anyString());
    }
    @Test void retirementHasGracePeriodAndFencesRegistrationRetryAndLateClaims() throws Exception {
        finishPreparation(); var cleaner = reclaimer(released()); cleaner.reclaim();
        assertEquals("RETIRED", registry.get(101L).getLifecycle());
        assertThrows(IllegalStateException.class, () -> registry.start(request()));
        assertThrows(IllegalStateException.class, () -> tasks.retryPreparation(101L));
        jdbc.update("UPDATE media_task SET status='READY'");
        assertNull(claim());
        jdbc.update("UPDATE media_task SET status='SUCCESS'");
        reclaimDue(false); cleaner.reclaim(); verify(storage, never()).delete(anyString());
        reclaimDue(true); cleaner.reclaim();
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM media_preparation_item WHERE status='DELETED'", Integer.class));
        verify(storage, times(4)).delete(startsWith("media-prepared/9/101/"));
        assertNotNull(registry.get(101L));
        // Repeated tombstone sweeps remove a late upload after its worker lost the lease.
        reclaimDue(true); cleaner.reclaim(); verify(storage, times(8)).delete(anyString());
    }
    @Test void partialDeletionIsRetriedAndForeignKeysAreNeverDeleted() throws Exception {
        finishPreparation(); var cleaner = reclaimer(released()); cleaner.reclaim();
        reclaimDue(true); doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(anyString());
        cleaner.reclaim();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_preparation_item WHERE status='DELETED'", Integer.class));
        doNothing().when(storage).delete(anyString());
        jdbc.update("UPDATE media_preparation_item SET object_key='media-prepared/9/other/image' WHERE role='IMAGE' AND item_index=0");
        reclaimDue(true); cleaner.reclaim(); verify(storage, never()).delete("media-prepared/9/other/image");
        jdbc.update("UPDATE media_preparation_item SET object_key='media-prepared/9/101/repaired' WHERE role='IMAGE' AND item_index=0");
        reclaimDue(true); cleaner.reclaim();
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM media_preparation_item WHERE status='DELETED'", Integer.class));
    }
    @Test void allocatedOrphanObjectIsReclaimedAfterDeadTask() {
        registry.start(request()); var execution = claim();
        var item = registry.reserve(execution, "IMAGE", 0, "{}");
        jdbc.update("UPDATE media_task SET status='DEAD'");
        var cleaner = reclaimer(released()); cleaner.reclaim();
        reclaimDue(true); cleaner.reclaim();
        verify(storage).delete(item.getObjectKey());
        assertThrows(IllegalStateException.class, () -> registry.confirmed(execution, item));
    }

    @Test void contentTasksHoldInputsAndRetiredPreparationRejectsEveryReentry() throws Exception {
        finishPreparation();
        var frozen = PreparedMedia.freeze(request(), registry.view(101L).manifest());
        var input = new PipelineContracts.Input(100L, 1L, 9L, "标题", "", "", frozen.coverObjectKey(), frozen.media(), System.currentTimeMillis());
        tasks.start(input); var cleaner = reclaimer(released()); cleaner.reclaim();
        assertEquals("ACTIVE", registry.get(101L).getLifecycle());
        jdbc.update("UPDATE media_task SET status='DEAD' WHERE phase='CONTENT'");
        jdbc.update("UPDATE media_pipeline SET status='WAITING_ARTIFACTS' WHERE id=100");
        reclaimDue(false); cleaner.reclaim(); assertEquals("RETIRED", registry.get(101L).getLifecycle());
        assertThrows(IllegalStateException.class, () -> tasks.retry(100L));
        assertThrows(IllegalStateException.class, () -> tasks.start(input));
        var row = tasks.listPipeline(100L, "CONTENT").get(0);
        jdbc.update("UPDATE media_task SET status='READY' WHERE id=?", row.getId());
        assertNull(tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).pipelineId(100L).phase("CONTENT").attempt(row.getAttempt()).build()));
        when(contentVideo.reviewSpec(100L, 0)).thenReturn(new PipelineContracts.ReviewSpec(true, "test", "v1", "rules", .2, .9, 12000, true, 4));
        jdbc.update("UPDATE media_pipeline SET status='COMPLETED' WHERE id=100");
        assertThrows(IllegalStateException.class, () -> tasks.startReview(100L));
    }
    @Test void concurrentRetirementAndExplicitRetryHaveOneSafeOutcome() {
        registry.start(request()); jdbc.update("UPDATE media_task SET status='DEAD'");
        var cleaner = reclaimer(released());
        var cleanup = CompletableFuture.runAsync(cleaner::reclaim);
        var retry = CompletableFuture.supplyAsync(() -> {
            try { return tasks.retryPreparation(101L); }
            catch (IllegalStateException retired) { return false; }
        });
        cleanup.join(); boolean retried = retry.join();
        if (retried) {
            assertEquals("ACTIVE", registry.get(101L).getLifecycle());
            assertEquals(MediaTaskStatus.READY, tasks.listByDraft(101L).get(0).getStatus());
        } else {
            assertEquals("RETIRED", registry.get(101L).getLifecycle());
            assertEquals(MediaTaskStatus.DEAD, tasks.listByDraft(101L).get(0).getStatus());
        }
        verify(storage, never()).delete(anyString());
    }

    Request nextImages(List<DynamicMedia.Image> images) {
        long duration = images.stream().mapToLong(DynamicMedia.Image::durationMs).sum();
        var dynamic = new DynamicMedia("horizontal", 1920, 1080, "cut", 0, images, null, duration);
        return new Request(102L, 1L, 1, 9L, new ContentMedia("dynamic", null, dynamic), images.get(0).imageObjectKey());
    }
    Request reordered() {
        return nextImages(List.of(new DynamicMedia.Image("upload/b", 0, 5000, .2, .8),
                new DynamicMedia.Image("upload/a", 5000, 3000, .5, .5)));
    }
    MediaTaskMessage claim(Long id) {
        var row = tasks.listByDraft(id).get(0);
        return tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).phase("DRAFT").attempt(row.getAttempt()).build());
    }
    Manifest prepareNext(Request request) throws Exception {
        registry.start(request); var execution = claim(request.preparationId());
        tasks.completeAndAdvance(execution, executor.execute(execution), null);
        return registry.view(request.preparationId()).manifest();
    }
    @Test void reorderingAndTimingReuseObjectsAndFreezeNewMapping() throws Exception {
        finishPreparation(); clearInvocations(storage, images);
        var manifest = prepareNext(reordered());
        assertEquals(registry.find(101L, "IMAGE", 1).getObjectKey(), manifest.items().get(0).objectKey());
        assertEquals(registry.find(101L, "OCR_IMAGE", 0).getObjectKey(), manifest.items().get(3).objectKey());
        assertEquals(5000, manifest.items().get(3).timeMs());
        var frozen = PreparedMedia.freeze(reordered(), manifest);
        PreparedMedia.validate(frozen.media(), frozen.coverObjectKey());
        assertEquals(8000, frozen.media().durationMs());
        assertEquals(.2, frozen.media().dynamic().items().get(0).focusX());
        verify(storage, never()).freeze(any(), anyString()); verify(storage, never()).upload(any(), anyString());
        verifyNoInteractions(images);
    }
    @Test void replacingOneImageOnlyPreparesTheReplacement() throws Exception {
        finishPreparation(); clearInvocations(storage, images);
        objects.put("upload/c", new PreparationStorage.Source("upload/c", "v1", "c-etag", 10));
        var next = nextImages(List.of(new DynamicMedia.Image("upload/a", 0, 3000, .5, .5),
                new DynamicMedia.Image("upload/c", 3000, 3000, .5, .5)));
        var manifest = prepareNext(next); assertNotNull(PreparedMedia.freeze(next, manifest));
        verify(storage, times(1)).freeze(any(), anyString()); verify(storage, times(1)).upload(any(), anyString());
        verify(images, times(1)).write(any(), any());
        assertEquals(101L, registry.find(102L, "IMAGE", 0).getObjectOwnerId());
        assertEquals(102L, registry.find(102L, "IMAGE", 1).getObjectOwnerId());
    }
    @Test void overwrittenSourceAndChangedParametersDoNotHitOldObjects() throws Exception {
        finishPreparation(); clearInvocations(images);
        objects.put("upload/a", new PreparationStorage.Source("upload/a", "v1", "changed-etag", 10));
        prepareNext(reordered()); verify(images, times(1)).write(any(), any());
        properties.setLongEdge(1280); clearInvocations(images);
        var request = new Request(103L, 1L, 2, 9L, reordered().media(), reordered().coverObjectKey());
        prepareNext(request); verify(images, times(2)).write(any(), any());
    }
    @Test void retiredOwnerKeepsReferencedObjectsAndReclaimsOnlyUnusedOnes() throws Exception {
        finishPreparation();
        var next = nextImages(List.of(new DynamicMedia.Image("upload/a", 0, 3000, .5, .5)));
        prepareNext(next);
        var video = released(); when(video.preparationReference(argThat(r -> r != null && r.preparationId().equals(102L))))
                .thenReturn("RETAINED");
        var cleaner = reclaimer(video); reclaimDue(false); cleaner.reclaim();
        assertEquals("RETIRED", registry.get(101L).getLifecycle());
        reclaimDue(true); cleaner.reclaim();
        verify(storage, never()).delete(registry.find(101L, "IMAGE", 0).getObjectKey());
        verify(storage).delete(registry.find(101L, "IMAGE", 1).getObjectKey());
        verify(storage).delete(registry.find(101L, "OCR_IMAGE", 1).getObjectKey());
        when(video.preparationReference(any())).thenReturn("RELEASED");
        reclaimDue(false); cleaner.reclaim(); reclaimDue(true); cleaner.reclaim();
        verify(storage).delete(registry.find(101L, "IMAGE", 0).getObjectKey());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_preparation_item WHERE status <> 'DELETED'", Integer.class));
    }
    @Test void borrowedObjectsStayProtectedDuringFailedPreparationAndAreImmutable() throws Exception {
        finishPreparation(); registry.start(reordered()); var execution = claim(102L);
        executor.execute(execution);
        var row = registry.find(102L, "IMAGE", 0);
        assertThrows(IllegalStateException.class, () -> registry.planned(execution, row, "{}"));
        assertThrows(IllegalStateException.class, () -> registry.confirmed(execution, row));
        tasks.completeAndAdvance(execution, null, "later stage failed");
        var video = released(); when(video.preparationReference(argThat(r -> r != null && r.preparationId().equals(102L))))
                .thenReturn("RETAINED");
        var cleaner = reclaimer(video); reclaimDue(false); cleaner.reclaim(); reclaimDue(true); cleaner.reclaim();
        verify(storage, never()).delete(anyString());
        assertEquals(MediaTaskStatus.RETRY_WAIT, registry.view(102L).status());
    }
    @Test void deletionFencePreventsNewReferencesAndDeadAttemptsCannotBorrow() throws Exception {
        finishPreparation(); var cleaner = reclaimer(released()); cleaner.reclaim(); reclaimDue(true);
        var deleting = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> { deleting.countDown(); assertTrue(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)); return null; })
                .when(storage).delete(anyString());
        var cleanup = CompletableFuture.runAsync(cleaner::reclaim);
        assertTrue(deleting.await(10, java.util.concurrent.TimeUnit.SECONDS));
        registry.start(reordered()); var execution = claim(102L);
        var source = registry.find(101L, "IMAGE", 0); var output = registry.find(101L, "OCR_IMAGE", 0);
        assertFalse(registry.shareImages(execution, 0, source, output));
        tasks.completeAndAdvance(execution, null, "lease ended");
        assertThrows(IllegalStateException.class, () -> registry.shareImages(execution, 0, source, output));
        resume.countDown(); cleanup.join();
        assertNull(registry.find(102L, "IMAGE", 0));
    }
    @Test void repeatedImageCanShareOneObjectAtTwoTimelinePositions() throws Exception {
        finishPreparation(); clearInvocations(images);
        var next = nextImages(List.of(new DynamicMedia.Image("upload/a", 0, 3000, .5, .5),
                new DynamicMedia.Image("upload/a", 3000, 3000, .5, .5)));
        var manifest = prepareNext(next);
        assertEquals(manifest.items().get(0).objectKey(), manifest.items().get(2).objectKey());
        assertEquals(3000, manifest.items().get(3).timeMs());
        assertNotNull(PreparedMedia.freeze(next, manifest)); verifyNoInteractions(images);
    }
    @Test void retiredReadyObjectCanBeBorrowedUntilItsDeletionFence() throws Exception {
        finishPreparation(); var cleaner = reclaimer(released()); cleaner.reclaim();
        assertEquals("RETIRED", registry.get(101L).getLifecycle());
        clearInvocations(images); assertNotNull(PreparedMedia.freeze(reordered(), prepareNext(reordered())));
        verifyNoInteractions(images);
    }

    @Test void sharingCannotCrossUserOrDraftOwnership() throws Exception {
        finishPreparation();
        var other = new Request(102L, 2L, 1, 10L, reordered().media(), reordered().coverObjectKey());
        registry.start(other); var execution = claim(102L);
        assertTrue(registry.reusableImages(execution, objects.get("upload/a")).isEmpty());
        assertFalse(registry.shareImages(execution, 0, registry.find(101L, "IMAGE", 0), registry.find(101L, "OCR_IMAGE", 0)));
        assertNull(registry.find(102L, "IMAGE", 0));
    }
    @Test void missingOldDerivativeCreatesNewOwnedPairInsteadOfWritingSharedKey() throws Exception {
        finishPreparation();
        var old = registry.find(101L, "OCR_IMAGE", 0); objects.remove(old.getObjectKey()); clearInvocations(images, storage);
        var manifest = prepareNext(reordered()); assertNotNull(PreparedMedia.freeze(reordered(), manifest));
        assertEquals(102L, registry.find(102L, "OCR_IMAGE", 1).getObjectOwnerId());
        assertNotEquals(old.getObjectKey(), registry.find(102L, "OCR_IMAGE", 1).getObjectKey());
        verify(images, times(1)).write(any(), any());
        verify(storage, never()).upload(any(), eq(old.getObjectKey()));
    }

}
