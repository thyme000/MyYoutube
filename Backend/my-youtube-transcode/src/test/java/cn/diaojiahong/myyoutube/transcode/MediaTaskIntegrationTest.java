package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.service.*;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(MediaTaskIntegrationTest.Config.class)
class MediaTaskIntegrationTest {
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.statement.StatementHandler.class,
            method="prepare", args={java.sql.Connection.class,Integer.class}))
    public static class SqlProbe implements org.apache.ibatis.plugin.Interceptor {
        static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);
        public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            SQL.get().add(((org.apache.ibatis.executor.statement.StatementHandler)call.getTarget()).getBoundSql().getSql());
            return call.proceed();
        }
    }
    @Configuration
    @MapperScan(basePackageClasses = MediaTaskMapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            return Boolean.getBoolean("content.integration") ? IsolatedDownloadSupport.database()
                    : new DriverManagerDataSource("jdbc:h2:mem:pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'", "sa", "");
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds); bean.setPlugins(new SqlProbe()); return bean.getObject();
        }
        @Bean TransactionTemplate transaction(DataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }
        @Bean MediaTaskDispatcher dispatcher() { return mock(MediaTaskDispatcher.class); }
        @Bean cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video() { return mock(cn.diaojiahong.myyoutube.video.api.ContentPipelinePort.class); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaTaskService tasks(MediaTaskMapper tasks, PipelineMapper pipelines, TransactionTemplate transaction,
                MediaTaskDispatcher dispatcher, SimpleMeterRegistry metrics, cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video) {
            return new MediaTaskService(tasks, pipelines, transaction, dispatcher, new PipelineProperties(), metrics, video);
        }
    }
    @Autowired MediaTaskService tasks;
    @Autowired MediaTaskDispatcher dispatcher;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;
    java.time.LocalDateTime past(int seconds) { return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(seconds); }
    Input input() { return new Input(100L, 1L, 9L, "标题", "简介", "", null, new cn.diaojiahong.myyoutube.common.media.ContentMedia("video", new cn.diaojiahong.myyoutube.common.media.VideoMedia("processed.mp4", 1000), null), 1700000000000L); }
    @BeforeEach void schema() {
        reset(dispatcher,video); metrics.clear();
        when(video.reviewSpec(anyLong(),eq(0))).thenReturn(new ReviewSpec(true,"test","v1","rules",0.2,0.9,12000,true,4));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS media_task"); jdbc.execute("DROP TABLE IF EXISTS media_pipeline");
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(dataSource);
    }
    MediaTask node(String type) { return tasks.listPipeline(100L, "CONTENT").stream().filter(t -> type.equals(t.getTaskType())).findFirst().orElseThrow(); }
    MediaTaskMessage claim(MediaTask row) {
        return tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).pipelineId(row.getPipelineId()).phase(row.getPhase()).attempt(row.getAttempt()).build());
    }
    String result(String type) {
        return switch (type) {
            case "COVER" -> "{\"coverObjectKey\":\"cover\"}";
            case "ASR" -> "{\"vttKey\":\"vtt\",\"segmentsKey\":\"segments\"}";
            default -> "{\"artifactKey\":\"" + type + "\",\"durationMs\":1000}";
        };
    }
    void succeed(String type) { tasks.completeAndAdvance(claim(node(type)), result(type), null); }
    void complete() { succeed("COVER"); succeed("ASR"); succeed("OCR"); succeed("SUMMARY"); succeed("TAG"); }
    @Test void draftContainsOnlyTranscodeAndContentHasFixedFiveNodes() {
        tasks.startDraft(1L, 9L, "raw.mp4"); tasks.startDraft(1L, 9L, "raw.mp4");
        assertEquals(List.of("TRANSCODE"), tasks.listByDraft(1L).stream().map(MediaTask::getTaskType).toList());
        tasks.start(input());
        jdbc.update("UPDATE media_pipeline SET input_json=? WHERE id=100",cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(input()).replace(",",", "));
        tasks.start(input());
        assertEquals(5, tasks.listPipeline(100L, "CONTENT").size());
        assertTrue(tasks.listPipeline(100L,"CONTENT").stream().allMatch(t -> t.getDraftInputJson() == null));
        assertEquals(input(),claim(node("OCR")).getInput());
        assertEquals("PENDING", node("SUMMARY").getStatus());
        assertEquals("READY", node("COVER").getStatus());
        assertEquals("PROCESSING", tasks.view(100L).status());
        assertThrows(IllegalStateException.class, () -> tasks.start(new Input(100L, 1L, 9L, "标题", "简介", "", null, new cn.diaojiahong.myyoutube.common.media.ContentMedia("video", new cn.diaojiahong.myyoutube.common.media.VideoMedia("different", 1000), null), 1700000000000L)));
    }
    @Test void nativeInputRunsSameFiveNodesWithoutDraftTranscodeOrSingleVideoKey() {
        var media = new cn.diaojiahong.myyoutube.common.media.DynamicMedia("vertical",1080,1920,"cut",0,
                List.of(new cn.diaojiahong.myyoutube.common.media.DynamicMedia.Image("first",0,3000,0.5,0.5)),null,3000);
        var input = new Input(100L,1L,9L,"图集","简介","","first",
                new cn.diaojiahong.myyoutube.common.media.ContentMedia("dynamic",null,media),1700000000000L);
        tasks.start(input); tasks.start(input);
        assertTrue(tasks.listByDraft(1L).isEmpty());
        assertEquals(5,tasks.listPipeline(100L,"CONTENT").size());
        var asr = claim(node("ASR")); assertNull(asr.getObjectKey()); assertEquals(input,asr.getInput());
        tasks.completeAndAdvance(asr,result("ASR"),null); succeed("OCR");
        assertEquals("READY",node("SUMMARY").getStatus());
        succeed("SUMMARY"); succeed("TAG"); assertEquals("PROCESSING",tasks.view(100L).status());
        succeed("COVER"); assertEquals("COMPLETED",tasks.view(100L).status());
        assertEquals(1L,tasks.view(100L).contentId());
    }
    @Test void concurrentClaimHasOneWinnerAndOldAttemptCannotComplete() {
        tasks.start(input()); var row = node("ASR");
        var a = CompletableFuture.supplyAsync(() -> claim(row));
        var b = CompletableFuture.supplyAsync(() -> claim(row));
        var first = a.join(); var second = b.join();
        assertEquals(1, (first == null ? 0 : 1)+(second == null ? 0 : 1));
        var winner = first == null ? second : first;
        var stale = MediaTaskMessage.builder().taskId(row.getId()).pipelineId(100L).attempt(0).build();
        tasks.completeAndAdvance(stale, result("ASR"), null);
        assertEquals("RUNNING", node("ASR").getStatus());
        tasks.completeAndAdvance(winner, result("ASR"), null);
        assertEquals("SUCCESS", node("ASR").getStatus());
        assertEquals(1, metrics.get("media.node.wait").tag("type","ASR").timer().count());
    }
    @Test void terminalBranchCannotForceManualWhileOtherBranchesCanProgress() {
        tasks.start(input());
        for (int i=0;i<4;i++) {
            tasks.completeAndAdvance(claim(node("ASR")), null, "provider unavailable");
            assertEquals("PROCESSING", tasks.view(100L).status());
            if (i<3) {
                assertEquals("RETRY_WAIT", node("ASR").getStatus());
                assertNull(claim(node("ASR")));
                jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), node("ASR").getId());
                tasks.recover();
            }
        }
        assertEquals("DEAD", node("ASR").getStatus()); assertEquals("BLOCKED", node("TAG").getStatus());
        succeed("OCR"); succeed("COVER");
        assertEquals("WAITING_ARTIFACTS", tasks.view(100L).status());
        String preserved = node("OCR").getResultJson();
        assertTrue(tasks.retry(100L));
        assertEquals(4, node("ASR").getAttempt());
        assertEquals(preserved, node("OCR").getResultJson());
        assertEquals(5, claim(node("ASR")).getAttempt());
        assertEquals("PROCESSING", tasks.view(100L).status());
    }
    @Test void concurrentCompletionsUnlockSummaryWithDurableDependencyPointers() {
        tasks.start(input());
        var a=claim(node("ASR")); var b=claim(node("OCR"));
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> tasks.completeAndAdvance(a,result("ASR"),null)),
                CompletableFuture.runAsync(() -> tasks.completeAndAdvance(b,result("OCR"),null))).join();
        assertEquals("READY",node("SUMMARY").getStatus());
        assertEquals(Set.of("ASR","OCR"),claim(node("SUMMARY")).getDependencies().keySet());
        tasks.completeAndAdvance(a,result("ASR"),null);
        assertEquals("RUNNING",node("SUMMARY").getStatus());
    }
    @Test void lostCompletionNotificationRecoversWithoutReexecutingNodes() {
        tasks.start(input());
        doThrow(new IllegalStateException("MQ down")).when(dispatcher).completed(any());
        complete();
        assertEquals("COMPLETED",tasks.view(100L).status());
        assertFalse(tasks.retry(100L));
        jdbc.update("UPDATE media_pipeline SET notify_after=? WHERE id=100", past(1));
        doNothing().when(dispatcher).completed(any());
        tasks.recoverCompletions();
        verify(dispatcher,times(2)).completed(any());
        tasks.received(100L);
        jdbc.update("UPDATE media_pipeline SET notify_after=? WHERE id=100", past(1));
        tasks.recoverCompletions();
        verify(dispatcher,times(2)).completed(any());
        assertEquals(1, metrics.get("media.pipeline.duration").timer().count());
    }
    @Test void reviewRetriesDoNotChangeCompletedArtifactPipeline() {
        tasks.start(input()); complete(); tasks.startReview(100L); tasks.startReview(100L);
        assertEquals(1,tasks.listPipeline(100L,"REVIEW").size());
        var row=tasks.listPipeline(100L,"REVIEW").get(0);
        tasks.completeAndAdvance(claim(row),null,"review down");
        assertEquals("COMPLETED",tasks.view(100L).status());
        assertEquals("RETRY_WAIT",tasks.listPipeline(100L,"REVIEW").get(0).getStatus());
    }
    @Test void staleWorkerLeaseIsRetriedAndLateResultIgnored() {
        tasks.start(input()); var old=claim(node("ASR"));
        jdbc.update("UPDATE media_task SET update_time=? WHERE id=?",past(600),old.getTaskId());
        tasks.recover(); assertEquals("RETRY_WAIT",node("ASR").getStatus());
        tasks.completeAndAdvance(old,result("ASR"),null);
        assertEquals("RETRY_WAIT",node("ASR").getStatus());
        assertThrows(IllegalStateException.class, () -> tasks.heartbeat(old.getTaskId(),old.getAttempt()));
    }
    @Test void effectiveCompletionLoadsOneTaskSnapshotAndNoInputRemoteRead() {
        tasks.start(input()); succeed("ASR"); var ocr=claim(node("OCR"));
        SqlProbe.SQL.get().clear();
        tasks.completeAndAdvance(ocr,result("OCR"),null);
        var sql=List.copyOf(SqlProbe.SQL.get());
        assertEquals(1,sql.stream().filter(q -> q.startsWith("SELECT") && q.contains("FROM media_task") && q.contains("pipeline_id =")).count());
        assertEquals(6,sql.size());
        verifyNoInteractions(video);
        System.out.println("DAG completion: SQL="+sql.size()+", taskSnapshots=1, inputRemoteQueries=0");
        SqlProbe.SQL.get().clear();
        tasks.completeAndAdvance(ocr,result("OCR"),null);
        assertEquals(2,SqlProbe.SQL.get().size());
    }
    @Test void failedPipelineCommitRollsBackTaskAndSendsNoCompletion() {
        tasks.start(input()); succeed("COVER"); succeed("ASR"); succeed("OCR"); succeed("SUMMARY");
        var tag=claim(node("TAG"));
        jdbc.execute("ALTER TABLE media_pipeline ADD CONSTRAINT reject_complete CHECK (status <> 'COMPLETED')");
        assertThrows(RuntimeException.class,() -> tasks.completeAndAdvance(tag,result("TAG"),null));
        assertEquals("RUNNING",node("TAG").getStatus());
        assertEquals("PROCESSING",tasks.view(100L).status());
        verify(dispatcher,never()).completed(any());
    }
    @Test void draftReceiptAfterCommitIsDurable() {
        tasks.startDraft(2L,9L,"raw.mp4");
        var execution=claim(tasks.listByDraft(2L).get(0));
        tasks.completeAndAdvance(execution,"{\"processedKey\":\"processed.mp4\",\"durationMs\":1000}",null);
        assertTrue(tasks.listByDraft(2L).get(0).getCompletionAck());
        tasks.recover(); verify(dispatcher,times(1)).draftFinished(2L,true,1000L,null);
    }
    @Test void recoveringContentSuccessWithoutDurationNeverSendsDraftCompletion() {
        tasks.start(input()); succeed("COVER"); succeed("ASR");
        var execution = claim(node("OCR"));
        jdbc.update("UPDATE media_task SET update_time=? WHERE id=?", past(600), execution.getTaskId());
        tasks.recover();
        assertEquals("RETRY_WAIT", node("OCR").getStatus());
        assertEquals("SUCCESS", node("COVER").getStatus());
        assertEquals("PROCESSING", tasks.view(100L).status());
        verify(dispatcher, never()).draftFinished(anyLong(), anyBoolean(), any(), any());
    }
    @Test void lostReadyDispatchIsRecoveredAndDuplicateDispatchCannotReexecute() {
        doThrow(new IllegalStateException("MQ down")).when(dispatcher).dispatch(any()); tasks.start(input());
        assertEquals("READY",node("ASR").getStatus());
        doNothing().when(dispatcher).dispatch(any());
        jdbc.update("UPDATE media_task SET dispatch_after=? WHERE status='READY'", past(1));
        tasks.recover(); verify(dispatcher,times(6)).dispatch(any());
        var candidate=node("ASR"); assertNotNull(claim(candidate)); assertNull(claim(candidate));
        assertEquals(1,metrics.get("media.claim.skipped").tag("phase","CONTENT").counter().count());
        System.out.println("Lost dispatch recovery: READY messages=3 original+3 recovered; duplicate claims=1; duplicate executions=0");
    }
    @Test void unexplainedPendingAndUnknownStatusAreOrchestrationErrors() {
        assertThrows(IllegalStateException.class,()->PipelineState.derive(List.of(new MediaTask().setStatus("PENDING"))));
        assertThrows(IllegalStateException.class,()->PipelineState.derive(List.of(new MediaTask().setStatus("INVALID"))));
    }
    @Test void delayedReviewRequestAfterDisableCannotCreateReviewExecution() {
        tasks.start(input()); complete();
        when(video.reviewSpec(100L,0)).thenReturn(new ReviewSpec(false,"test","v1","rules",0.2,0.9,12000,true,4));
        tasks.startReview(100L); assertTrue(tasks.listPipeline(100L,"REVIEW").isEmpty());
    }
}
