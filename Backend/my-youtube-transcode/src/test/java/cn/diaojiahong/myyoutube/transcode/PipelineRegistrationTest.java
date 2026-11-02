package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.media.VideoMedia;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.observability.PipelineEvents;
import cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaPreparationMapper;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import cn.diaojiahong.myyoutube.transcode.mapper.PipelineMapper;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparationLifecycle;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskDispatcher;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.transcode.service.PipelineEventJournal;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(MediaTaskIntegrationTest.Config.class)
@Timeout(60)
class PipelineRegistrationTest {
    @Autowired DataSource dataSource;
    @Autowired MediaTaskMapper taskRows;
    @Autowired PipelineMapper pipelines;
    @Autowired MediaPreparationMapper preparations;
    @Autowired TransactionTemplate transaction;
    @Autowired MediaTaskDispatcher dispatcher;
    @Autowired ContentPipelinePort video;
    @Autowired MeterRegistry metrics;
    @Autowired PipelineEvents events;
    @Autowired PipelineObservationProperties observations;

    JdbcTemplate jdbc;
    PreparationLifecycle lifecycle;
    MediaTaskService tasks;

    @BeforeEach
    void setUp() throws Exception {
        reset(dispatcher, video);
        observations.getRecording().setEnabled(true);
        jdbc = new JdbcTemplate(dataSource);
        OutboxTestDatabase.reset(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS media_task");
        jdbc.execute("DROP TABLE IF EXISTS media_pipeline");
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(dataSource);

        lifecycle = spy(new PreparationLifecycle(preparations));
        var journal = new PipelineEventJournal(taskRows, pipelines, lifecycle, events);
        tasks = new MediaTaskService(taskRows, lifecycle, pipelines, transaction, dispatcher,
                new PipelineProperties(), metrics, video, journal);
    }

    Input input(long pipelineId, long contentId) {
        return new Input(pipelineId, contentId, 9L, "标题", "简介", "", null,
                new ContentMedia("video", new VideoMedia("processed.mp4", 1000), null), 1700000000000L);
    }

    @RepeatedTest(5)
    void newRegistrationsInTheSameGapBothCommit() throws Exception {
        var validation = new CyclicBarrier(2);
        // 让两个登记事务都到达素材校验；旧实现此时各自持有同一主键间隙锁。
        doAnswer(call -> {
            validation.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(lifecycle).requireActive(any(ContentMedia.class));

        together(List.of(() -> tasks.start(input(100L, 1L)), () -> tasks.start(input(101L, 2L))));

        assertNotNull(pipelines.selectById(100L));
        assertNotNull(pipelines.selectById(101L));
        assertEquals(5, tasks.listPipeline(100L, "CONTENT").size());
        assertEquals(5, tasks.listPipeline(101L, "CONTENT").size());
        verify(dispatcher, times(6)).dispatch(any());
    }

    @Test
    void concurrentFirstDeliveriesCreateOneGraph() throws Exception {
        Input input = input(100L, 1L);
        together(Collections.nCopies(8, () -> tasks.start(input)));

        assertEquals(1, count("media_pipeline"));
        assertEquals(5, count("media_task"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pipeline_event_outbox WHERE payload LIKE '%PIPELINE_STARTED%'", Integer.class));
        verify(dispatcher, times(3)).dispatch(any());
    }

    @Test
    void concurrentReplaysPreserveRunningTasksAndEvents() throws Exception {
        Input input = input(100L, 1L);
        tasks.start(input);
        MediaTask row = tasks.listPipeline(100L, "CONTENT").stream()
                .filter(task -> "ASR".equals(task.getTaskType())).findFirst().orElseThrow();
        assertNotNull(tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).pipelineId(100L)
                .phase("CONTENT").attempt(row.getAttempt()).build()));
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM media_task ORDER BY id");
        int eventCount = count("pipeline_event_outbox");
        reset(dispatcher);

        together(Collections.nCopies(8, () -> tasks.start(input)));

        assertEquals(before, jdbc.queryForList("SELECT * FROM media_task ORDER BY id"));
        assertEquals(MediaTaskStatus.RUNNING, taskRows.selectById(row.getId()).getStatus());
        assertEquals(eventCount, count("pipeline_event_outbox"));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void anotherPipelineCannotReuseTheContentId() {
        tasks.start(input(100L, 1L));
        reset(dispatcher);

        assertThrows(DuplicateKeyException.class, () -> tasks.start(input(101L, 1L)));

        assertNull(pipelines.selectById(101L));
        assertEquals(1, count("media_pipeline"));
        assertEquals(5, count("media_task"));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void nodeInsertFailureRollsBackRegistrationAndAllowsRedelivery() {
        jdbc.execute("ALTER TABLE media_task ADD CONSTRAINT reject_ocr CHECK (task_type <> 'OCR')");

        assertThrows(RuntimeException.class, () -> tasks.start(input(100L, 1L)));

        assertEquals(0, count("media_pipeline"));
        assertEquals(0, count("media_task"));
        assertEquals(0, count("pipeline_event_outbox"));
        verifyNoInteractions(dispatcher);

        jdbc.execute("ALTER TABLE media_task DROP CONSTRAINT reject_ocr");
        tasks.start(input(100L, 1L));
        assertEquals(1, count("media_pipeline"));
        assertEquals(5, count("media_task"));
        verify(dispatcher, times(3)).dispatch(any());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void together(List<Runnable> actions) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(actions.size());
        var start = new CyclicBarrier(actions.size());
        var futures = new ArrayList<Future<?>>();
        try {
            for (Runnable action : actions) futures.add(workers.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                action.run();
                return null;
            }));
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
    }
}
