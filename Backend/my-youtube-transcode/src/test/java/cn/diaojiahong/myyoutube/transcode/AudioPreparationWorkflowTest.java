package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts.*;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import static cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException.Reason.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import cn.diaojiahong.myyoutube.transcode.service.*;
import cn.diaojiahong.myyoutube.transcode.task.AudioPrepareTaskExecutor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(MediaTaskIntegrationTest.Config.class)
class AudioPreparationWorkflowTest {
    @Autowired DataSource database;
    @Autowired MediaTaskService tasks;
    @Autowired MediaTaskMapper rows;
    @Autowired MediaTaskDispatcher dispatcher;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    JdbcTemplate jdbc;
    PreparationStorage storage;
    PreparedAudioReader reader;
    PreparedImageWriter images;
    AudioPrepareTaskExecutor executor;
    final Map<String, PreparationStorage.Source> objects = new HashMap<>();
    Request request() { return new Request(501L, 9L, "uploads/audio/9/day/a.wav", "uploads/picture/9/day/cover.png"); }
    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(database); reset(dispatcher); observations.getRecording().setEnabled(false);
        OutboxTestDatabase.reset(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS media_task"); jdbc.execute("DROP TABLE IF EXISTS media_pipeline");
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(database);
        storage = mock(PreparationStorage.class); reader = mock(PreparedAudioReader.class); images = mock(PreparedImageWriter.class);
        executor = new AudioPrepareTaskExecutor(storage, reader, images, new AudioPreparationProperties());
        objects.clear();
        for (String key : List.of(request().objectKey(), request().coverObjectKey())) objects.put(key, new PreparationStorage.Source(key, "v1", "original", 10));
        when(storage.inspectIfPresent(anyString())).thenAnswer(c -> objects.get(c.getArgument(0)));
        when(storage.inspect(anyString())).thenAnswer(c -> Objects.requireNonNull(objects.get(c.getArgument(0))));
        when(storage.freeze(any(), anyString())).thenAnswer(c -> {
            PreparationStorage.Source original = c.getArgument(0); String key = c.getArgument(1);
            var frozen = new PreparationStorage.Source(key, "frozen-v1", "frozen", original.bytes()); objects.put(key, frozen); return frozen;
        });
        doAnswer(c -> { Files.write(c.getArgument(1), new byte[10]); return null; }).when(storage).download(any(), any(), anyLong());
        when(reader.read(any())).thenReturn(new PreparedAudioReader.Probe(1234, "audio/wav"));
        when(images.write(any(), any())).thenAnswer(c -> { Files.write(c.getArgument(1), new byte[4]); return new PreparedImageWriter.Result(100, 100, 80, 80, 1, 4); });
        doAnswer(c -> { String key = c.getArgument(1); objects.put(key, new PreparationStorage.Source(key, "out-v1", "cover", 4)); return null; }).when(storage).upload(any(), anyString());
    }
    MediaTaskMessage message() {
        var row = rows.selectList(null).get(0);
        return MediaTaskMessage.builder().taskId(row.getId()).phase(row.getPhase()).attempt(row.getAttempt()).build();
    }
    @Test void registrationIsIdempotentAndDoesNotCreateFakeContentOrPreparation() {
        tasks.startAudioPreparation(request()); tasks.startAudioPreparation(request());
        assertEquals(1, rows.selectCount(null)); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_pipeline", Integer.class));
        assertEquals(MediaTaskStatus.READY, tasks.audioPreparationView(501L).status());
        var changed = new Request(501L, 9L, "uploads/audio/9/day/other.wav", null);
        assertThrows(IllegalArgumentException.class, () -> tasks.startAudioPreparation(changed));
    }
    @Test void workerUsesPersistedAudioInputAndDoesNotNeedPipelineInput() throws Exception {
        tasks.startAudioPreparation(request()); var incoming = message(); incoming.setAudioInput(new Request(501L, 7L, "uploads/audio/7/foreign.wav", null));
        var claimed = tasks.claim(incoming); assertEquals(request(), claimed.getAudioInput()); assertNull(claimed.getInput());
        tasks.completeAndAdvance(claimed, executor.execute(claimed), null);
        var result = tasks.audioPreparationView(501L); assertEquals(MediaTaskStatus.SUCCESS, result.status()); result.result().validate(request());
        assertTrue(rows.selectList(null).get(0).getCompletionAck()); assertNull(tasks.claim(incoming));
    }
    @Test void existingWorkerExecutesAudioAndWritesTaskEventsWithoutFakeContentIds() {
        observations.getRecording().setEnabled(true); tasks.startAudioPreparation(request());
        try (var worker = new WorkerClose(new MediaTaskWorker(tasks, List.of(executor)))) { worker.value.execute(message()); }
        assertEquals(MediaTaskStatus.SUCCESS, tasks.audioPreparationView(501L).status());
        var events = jdbc.queryForList("SELECT payload FROM pipeline_event_outbox", String.class);
        assertEquals(3, events.size());
        for (String payload : events) {
            var event = cn.diaojiahong.myyoutube.common.observability.PipelineEventJson.decode(payload);
            assertNull(event.contentId()); assertNull(event.draftId()); assertNotNull(event.taskId());
        }
    }
    @Test void deterministicRejectionStopsWithoutAutomaticRetries() throws Exception {
        observations.getRecording().setEnabled(true);
        tasks.startAudioPreparation(request()); when(reader.read(any())).thenThrow(new InvalidMediaInputException(AUDIO_DECODE_FAILED));
        try (var worker = new WorkerClose(new MediaTaskWorker(tasks, List.of(executor)))) { worker.value.execute(message()); }
        var result = tasks.audioPreparationView(501L); assertEquals(MediaTaskStatus.DEAD, result.status()); assertEquals(0, result.retryCount());
        assertEquals(AUDIO_DECODE_FAILED.code(), result.failureCode());
        assertEquals(AUDIO_DECODE_FAILED.code(), result.errorMessage());
    }
    @Test void internalOutputValidationFailureUsesTechnicalRetry() throws Exception {
        observations.getRecording().setEnabled(true);
        tasks.startAudioPreparation(request()); when(reader.read(any())).thenReturn(new PreparedAudioReader.Probe(0, "audio/wav"));
        try (var worker = new WorkerClose(new MediaTaskWorker(tasks, List.of(executor)))) { worker.value.execute(message()); }
        var result = tasks.audioPreparationView(501L);
        assertEquals(MediaTaskStatus.RETRY_WAIT, result.status()); assertEquals(1, result.retryCount());
        assertEquals("TASK_EXECUTION_FAILED", result.failureCode());
    }
    @Test void ordinaryIllegalArgumentExceptionIsNotAUserFileRejection() throws Exception {
        tasks.startAudioPreparation(request()); when(reader.read(any())).thenThrow(new IllegalArgumentException("internal contract bug"));
        try (var worker = new WorkerClose(new MediaTaskWorker(tasks, List.of(executor)))) { worker.value.execute(message()); }
        assertEquals(MediaTaskStatus.RETRY_WAIT, tasks.audioPreparationView(501L).status());
    }
    @Test void infrastructureFailureUsesExistingRetryAndAttemptFence() throws Exception {
        tasks.startAudioPreparation(request()); var first = tasks.claim(message()); tasks.completeAndAdvance(first, null, "storage unavailable");
        assertEquals(MediaTaskStatus.RETRY_WAIT, tasks.audioPreparationView(501L).status());
        jdbc.update("UPDATE media_task SET next_retry_at=?", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)); tasks.recover();
        var second = tasks.claim(message()); assertEquals(2, second.getAttempt());
        tasks.completeAndAdvance(first, executor.execute(first), null); assertEquals(MediaTaskStatus.RUNNING, tasks.audioPreparationView(501L).status());
        tasks.completeAndAdvance(second, executor.execute(second), null); assertEquals(MediaTaskStatus.SUCCESS, tasks.audioPreparationView(501L).status());
    }
    @Test void sourceReplacementAfterFirstFreezeDoesNotChangeRetriedInput() throws Exception {
        tasks.startAudioPreparation(request()); var claimed = tasks.claim(message()); executor.execute(claimed);
        objects.put(request().objectKey(), new PreparationStorage.Source(request().objectKey(), "v2", "replaced", 999));
        objects.put(request().coverObjectKey(), new PreparationStorage.Source(request().coverObjectKey(), "v2", "replaced", 999));
        clearInvocations(storage); var result = JsonUtil.fromJson(executor.execute(claimed), Result.class);
        assertEquals(10, result.bytes()); verify(storage, never()).inspect(request().objectKey()); verify(storage, never()).inspect(request().coverObjectKey());
        verify(storage, never()).freeze(any(), anyString());
    }
    @Test void overBudgetInputIsRejectedBeforeCopyOrDownload() throws Exception {
        tasks.startAudioPreparation(request()); objects.put(request().objectKey(), new PreparationStorage.Source(request().objectKey(), "v1", "original", 200L * 1024 * 1024));
        var claimed = tasks.claim(message()); assertThrows(IllegalArgumentException.class, () -> executor.execute(claimed));
        verify(storage, never()).freeze(any(), anyString()); verify(storage, never()).download(any(), any(), anyLong());
    }
    @Test void retryReusesTaskAndDoesNotResetSuccessfulOutput() throws Exception {
        tasks.startAudioPreparation(request()); var first = tasks.claim(message()); tasks.rejectAudioPreparation(first, INVALID_AUDIO);
        assertTrue(tasks.retryAudioPreparation(501L)); var second = tasks.claim(message()); assertEquals(2, second.getAttempt());
        tasks.rejectAudioPreparation(first, INVALID_AUDIO); assertEquals(MediaTaskStatus.RUNNING, tasks.audioPreparationView(501L).status());
        tasks.completeAndAdvance(second, executor.execute(second), null); assertTrue(tasks.retryAudioPreparation(501L));
        assertEquals(MediaTaskStatus.SUCCESS, tasks.audioPreparationView(501L).status()); assertEquals(1, rows.selectCount(null));
    }
    @Test void registrationRejectsForeignCoverBeforeCreatingTask() {
        assertThrows(IllegalArgumentException.class, () -> tasks.startAudioPreparation(new Request(501L, 9L, request().objectKey(), "uploads/picture/7/day/cover.png")));
        assertEquals(0, rows.selectCount(null));
    }
    @Test void concurrentRegistrationIsIdempotent() {
        var first = java.util.concurrent.CompletableFuture.runAsync(() -> tasks.startAudioPreparation(request()));
        var second = java.util.concurrent.CompletableFuture.runAsync(() -> tasks.startAudioPreparation(request()));
        java.util.concurrent.CompletableFuture.allOf(first, second).join();
        assertEquals(1, rows.selectCount(null)); assertEquals(MediaTaskStatus.READY, tasks.audioPreparationView(501L).status());
    }
    record WorkerClose(MediaTaskWorker value) implements AutoCloseable { @Override public void close() { value.close(); } }
}
