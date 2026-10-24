package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import cn.diaojiahong.myyoutube.transcode.domain.po.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.service.*;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig({MediaTaskIntegrationTest.Config.class, MediaRetryHistoryTest.Config.class})
class MediaRetryHistoryTest {
    @Configuration static class Config {
        @Bean RetryBatchOccupation occupation() { return mock(RetryBatchOccupation.class); }
        @Bean RetryBatchStore history(MediaRetryBatchMapper batches, MediaTaskMapper tasks,
                RetryBatchOccupation occupation, TransactionTemplate tx) {
            return new RetryBatchStore(batches, tasks, occupation, tx);
        }
    }
    @Autowired DataSource database;
    @Autowired MediaRetryBatchMapper batches;
    @Autowired MediaTaskMapper tasks;
    @Autowired RetryBatchStore history;

    @BeforeEach void reset() {
        var jdbc = new JdbcTemplate(database);
        for (String table : List.of("media_retry_batch", "media_task", "media_pipeline")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(database);
    }
    MediaRetryBatch row(long id) {
        return new MediaRetryBatch().setId(id).setRequestId("history-" + id).setActorId(1L).setStatus(MediaRetryBatchStatus.COMPLETED)
                .setMaxConcurrency(1).setSelectionJson("[]").setCreatedAt(LocalDateTime.parse("2026-10-18T12:00:00"));
    }
    @Test void batchStatusPersistsAsItsCodeAndSupportsConditionalUpdates() {
        var row = row(1).setStatus(MediaRetryBatchStatus.QUEUED);
        batches.insert(row);
        var jdbc = new JdbcTemplate(database);
        for (var status : MediaRetryBatchStatus.values()) {
            var previous = batches.selectById(row.getId()).getStatus();
            assertEquals(1, batches.update(null, new LambdaUpdateWrapper<MediaRetryBatch>()
                    .eq(MediaRetryBatch::getId, row.getId()).eq(MediaRetryBatch::getStatus, previous)
                    .set(MediaRetryBatch::getStatus, status)));
            assertEquals(status.getCode(), jdbc.queryForObject("SELECT status FROM media_retry_batch WHERE id=?", String.class, row.getId()));
            assertEquals(status, batches.selectById(row.getId()).getStatus());
        }
    }
    @Test void historyUsesDescendingCursorAndDoesNotRereadFrozenTaskResults() {
        for (long id : List.of(1L,2L,3L)) {
            var row = row(id);
            var view = new Batch(id,MediaRetryBatchStatus.COMPLETED,0,0,0,0,0,0,0,0,null,row.getCreatedAt(),null);
            row.setResultJson(JsonUtil.toJson(new RetryBatchStore.Snapshot(view,List.of(),List.of())));
            batches.insert(row);
        }
        MediaTaskIntegrationTest.SqlProbe.SQL.get().clear();
        var first = history.history(null,2);
        assertEquals(List.of(3L,2L),first.items().stream().map(Batch::batchId).toList());
        assertEquals(2L,first.nextCursor());
        var second = history.history(first.nextCursor(),2);
        assertEquals(List.of(1L),second.items().stream().map(Batch::batchId).toList());
        assertNull(second.nextCursor());
        assertTrue(MediaTaskIntegrationTest.SqlProbe.SQL.get().stream().noneMatch(sql -> sql.contains("FROM media_task")));
    }
    @Test void listingHistoryLazilyFreezesAnUnfinishedRecordWhoseTasksHaveEnded() {
        var row = row(4).setStatus(MediaRetryBatchStatus.RUNNING).setActive(1)
                .setSelectionJson(JsonUtil.toJson(List.of(new Item(15L,MediaRetryItemState.PENDING,1,null,null))));
        batches.insert(row);
        tasks.insert(new MediaTask().setId(15L).setOwnerId(1L).setRetryBatchId(4L).setPhase("DRAFT")
                .setTaskType("TRANSCODE").setStatus(MediaTaskStatus.SUCCESS).setAttempt(2).setRetryCount(0)
                .setCompletionAck(false).setDependenciesJson("{}"));
        assertEquals(MediaRetryBatchStatus.COMPLETED,history.history(null,20).items().get(0).status());
        assertNull(batches.selectById(4L).getActive());
        assertNotNull(batches.selectById(4L).getResultJson());
    }
    @Test void emptyHistoryHasNoContinuation() {
        var page = history.history(null, 20);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }
    @Test void invalidHistoryPagesAreRejected() {
        assertThrows(BizException.class,() -> history.history(-1L,20));
        assertThrows(BizException.class,() -> history.history(null,0));
        assertThrows(BizException.class,() -> history.history(null,101));
    }
}
