package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Query;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import cn.diaojiahong.myyoutube.transcode.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(MediaTaskIntegrationTest.Config.class)
class MediaRetryQueryTest {
    @Autowired DataSource database;
    @Autowired MediaTaskMapper rows;
    @Autowired TransactionTemplate transaction;
    MediaRetryService service;

    @BeforeEach
    void setup() {
        var jdbc = new JdbcTemplate(database);
        for (String table : List.of("media_retry_batch", "media_task", "media_pipeline")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(database);
        service = new MediaRetryService(rows, mock(MediaTaskService.class), mock(RetryBatchStore.class),
                mock(RetryOwnerChecks.class), transaction, mock(RetryBatchOccupation.class), new PipelineProperties());
    }

    void task(long id, String resource) {
        rows.insert(new MediaTask().setId(id).setOwnerId(id).setPhase("DRAFT").setTaskType("TRANSCODE")
                .setStatus(MediaTaskStatus.DEAD).setAttempt(0).setRetryCount(0).setCompletionAck(false)
                .setDependenciesJson("{}").setDependencyService(resource));
    }

    Query query(Long cursor, String resource, int size) {
        return new Query(null, resource, null, null, null, null, cursor, size, null);
    }

    @Test
    void filteredEmptyPageStillAdvancesUsingTheLastScannedLargeId() {
        long id = 9_007_199_254_740_993L;
        task(id, "OCR");
        task(id + 1, "LLM");
        CursorPage<MediaRetryPort.Task,Long> first = service.query(query(null, "LLM", 1));
        assertTrue(first.items().isEmpty());
        assertEquals(id, first.nextCursor());
        CursorPage<MediaRetryPort.Task,Long> second = service.query(query(first.nextCursor(), "LLM", 1));
        assertEquals(id + 1, second.items().get(0).taskId());
        CursorPage<MediaRetryPort.Task,Long> end = service.query(query(second.nextCursor(), "LLM", 1));
        assertTrue(end.items().isEmpty());
        assertNull(end.nextCursor());
    }

    @Test
    void shortFinalPageHasNoContinuationAndKeepsAscendingOrder() {
        task(3, "LLM");
        task(2, "LLM");
        CursorPage<MediaRetryPort.Task,Long> page = service.query(query(null, null, 20));
        assertEquals(
                List.of(2L, 3L),
                page.items().stream().map(cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Task::taskId).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void statusFilterUsesEnumCodesAndPreservesAllAndDefaultSelections() {
        for (MediaTaskStatus status : MediaTaskStatus.values()) {
            long id = status.ordinal() + 1L;
            task(id, "LLM");
            rows.updateById(rows.selectById(id).setStatus(status));
        }
        for (MediaTaskStatus status : MediaTaskStatus.values()) {
            CursorPage<MediaRetryPort.Task,Long> page = service.query(new Query(
                    null,
                    null,
                    null,
                    status.getCode(),
                    null,
                    null,
                    null,
                    20,
                    null));
            assertEquals(List.of(status), page.items()
                    .stream()
                    .map(cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Task::status)
                    .toList());
        }
        assertEquals(MediaTaskStatus.values().length,
                service.query(new Query(null, null, null, "ALL", null, null, null, 20, null)).items().size());
        assertEquals(java.util.Set.of(MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED, MediaTaskStatus.RETRY_WAIT),
                service.query(query(null, null, 20)).items().stream()
                        .map(cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Task::status)
                        .collect(java.util.stream.Collectors.toSet()));
        assertThrows(BizException.class,
                () -> service.query(new Query(null, null, null, "INVALID", null, null, null, 20, null)));
    }
}
