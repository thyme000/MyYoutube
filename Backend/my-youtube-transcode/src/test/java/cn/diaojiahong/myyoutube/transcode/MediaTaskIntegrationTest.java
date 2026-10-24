package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Retryer;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import cn.diaojiahong.myyoutube.common.feign.ModelErrorDecoder;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import cn.diaojiahong.myyoutube.transcode.task.MediaTaskExecutor;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.domain.po.Pipeline;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
            String retryDatabase=System.getProperty("retry.mysql.url");
            if (retryDatabase!=null) {
                if (!retryDatabase.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/retry_it_[A-Za-z0-9_]+[?].*"))
                    throw new IllegalArgumentException("Retry MySQL tests require an isolated local retry_it_ database");
                return new DriverManagerDataSource(retryDatabase,"root","retry-it-only");
            }
            return Boolean.getBoolean("content.integration") ? IsolatedDownloadSupport.database()
                    : new DriverManagerDataSource("jdbc:h2:mem:pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'", "sa", "");
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds); bean.setPlugins(new SqlProbe()); return bean.getObject();
        }
        @Bean TransactionTemplate transaction(DataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }
        @Bean MediaTaskDispatcher dispatcher() { return mock(MediaTaskDispatcher.class); }
        @Bean cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video() { return mock(cn.diaojiahong.myyoutube.video.api.ContentPipelinePort.class); }
        @Bean cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations() { return new cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties(); }
        @Bean cn.diaojiahong.myyoutube.common.observability.PipelineEvents events(DataSource ds, TransactionTemplate tx, cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties props) {
            return new cn.diaojiahong.myyoutube.common.observability.PipelineEvents(props,
                    new cn.diaojiahong.myyoutube.common.observability.PipelineEventRecorder(new JdbcTemplate(ds), props, (DataSourceTransactionManager)tx.getTransactionManager()), "transcode", java.time.Clock.systemUTC());
        }
        @Bean io.micrometer.core.instrument.MeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaTaskService tasks(MediaTaskMapper tasks, PipelineMapper pipelines, TransactionTemplate transaction,
                MediaTaskDispatcher dispatcher, io.micrometer.core.instrument.MeterRegistry metrics, cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video,
                MediaPreparationMapper preparations, cn.diaojiahong.myyoutube.common.observability.PipelineEvents events) {
            var lifecycle = new cn.diaojiahong.myyoutube.transcode.preparation.PreparationLifecycle(preparations);
            var journal = new cn.diaojiahong.myyoutube.transcode.service.PipelineEventJournal(tasks, pipelines, lifecycle, events);
            return new MediaTaskService(tasks, lifecycle, pipelines, transaction, dispatcher, new PipelineProperties(), metrics, video, journal);
        }
    }
    @Autowired MediaTaskService tasks;
    @Autowired MediaTaskMapper taskRows;
    @Autowired PipelineMapper pipelineRows;
    @Autowired MediaTaskDispatcher dispatcher;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video;
    @Autowired DataSource dataSource;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    @Autowired TransactionTemplate transaction;
    JdbcTemplate jdbc;
    java.time.LocalDateTime past(int seconds) { return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(seconds); }
    Input input() { return new Input(100L, 1L, 9L, "标题", "简介", "", null, new cn.diaojiahong.myyoutube.common.media.ContentMedia("video", new cn.diaojiahong.myyoutube.common.media.VideoMedia("processed.mp4", 1000), null), 1700000000000L); }
    @BeforeEach void schema() throws Exception {
        reset(dispatcher,video); metrics.clear(); observations.getRecording().setEnabled(false);
        when(video.reviewSpec(anyLong(),eq(0))).thenReturn(new ReviewSpec(true,"test","v1","rules",0.2,0.9,12000,true,4));
        jdbc = new JdbcTemplate(dataSource);
        OutboxTestDatabase.reset(jdbc);
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
    @Test void statusEnumsKeepDatabaseCodesAndSupportConditionalUpdates() {
        tasks.start(input());
        var task = node("ASR");
        for (var status : MediaTaskStatus.values()) {
            var previous = taskRows.selectById(task.getId()).getStatus();
            assertEquals(1, taskRows.update(null, new LambdaUpdateWrapper<MediaTask>()
                    .eq(MediaTask::getId, task.getId()).eq(MediaTask::getStatus, previous).set(MediaTask::getStatus, status)));
            assertEquals(status.getCode(), jdbc.queryForObject("SELECT status FROM media_task WHERE id=?", String.class, task.getId()));
            assertEquals(status, taskRows.selectById(task.getId()).getStatus());
        }
        for (var status : PipelineStatus.values()) {
            var pipeline = pipelineRows.selectById(100L);
            pipeline.setStatus(status);
            assertEquals(1, pipelineRows.updateById(pipeline));
            assertEquals(status.getCode(), jdbc.queryForObject("SELECT status FROM media_pipeline WHERE id=100", String.class));
            assertEquals(status, pipelineRows.selectById(100L).getStatus());
            assertEquals(1L, pipelineRows.selectCount(new LambdaQueryWrapper<Pipeline>().eq(Pipeline::getStatus, status)));
        }
    }

    @Test void circuitAndExhaustedRecoveryStopWithoutSpendingExecutionRetries() {
        tasks.start(input());
        tasks.deferAndAdvance(claim(node("ASR")), new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("ASR", "MODEL_CIRCUIT_OPEN", 30, null));
        tasks.deferAndAdvance(claim(node("OCR")), new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("OCR", "MODEL_RECOVERY_EXHAUSTED", 30, null));
        assertEquals(MediaTaskStatus.DEAD, node("ASR").getStatus());
        assertEquals(0, node("ASR").getRetryCount());
        assertNull(node("ASR").getNextRetryAt());
        assertEquals("ASR", node("ASR").getDependencyService());
        assertEquals(MediaTaskStatus.BLOCKED, node("SUMMARY").getStatus());
        succeed("COVER");
        assertEquals(PipelineStatus.WAITING_ARTIFACTS, tasks.view(100L).status());
        tasks.recover();
        assertEquals(MediaTaskStatus.DEAD, node("OCR").getStatus());
    }

    @Test void draftContainsOnlyTranscodeAndContentHasFixedFiveNodes() {
        tasks.startDraft(1L, 9L, "raw.mp4"); tasks.startDraft(1L, 9L, "raw.mp4");
        assertEquals(List.of("TRANSCODE"), tasks.listByDraft(1L).stream().map(MediaTask::getTaskType).toList());
        tasks.start(input());
        jdbc.update("UPDATE media_pipeline SET input_json=? WHERE id=100",cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(input()).replace(",",", "));
        tasks.start(input());
        assertEquals(5, tasks.listPipeline(100L, "CONTENT").size());
        assertTrue(tasks.listPipeline(100L,"CONTENT").stream().allMatch(t -> t.getDraftInputJson() == null));
        assertEquals(input(),claim(node("OCR")).getInput());
        assertEquals(MediaTaskStatus.PENDING, node("SUMMARY").getStatus());
        assertEquals(MediaTaskStatus.READY, node("COVER").getStatus());
        assertEquals(PipelineStatus.PROCESSING, tasks.view(100L).status());
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
        assertEquals(MediaTaskStatus.READY,node("SUMMARY").getStatus());
        succeed("SUMMARY"); succeed("TAG"); assertEquals(PipelineStatus.PROCESSING,tasks.view(100L).status());
        succeed("COVER"); assertEquals(PipelineStatus.COMPLETED,tasks.view(100L).status());
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
        assertEquals(MediaTaskStatus.RUNNING, node("ASR").getStatus());
        tasks.completeAndAdvance(winner, result("ASR"), null);
        assertEquals(MediaTaskStatus.SUCCESS, node("ASR").getStatus());
        assertEquals(1, metrics.get("media.node.wait").tag("type","ASR").timer().count());
    }
    @Test void terminalBranchCannotForceManualWhileOtherBranchesCanProgress() {
        observations.getRecording().setEnabled(true);
        tasks.start(input());
        for (int i=0;i<4;i++) {
            tasks.completeAndAdvance(claim(node("ASR")), null, "provider unavailable");
            assertEquals(PipelineStatus.PROCESSING, tasks.view(100L).status());
            if (i<3) {
                assertEquals(MediaTaskStatus.RETRY_WAIT, node("ASR").getStatus());
                assertNull(claim(node("ASR")));
                jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), node("ASR").getId());
                tasks.recover();
            }
        }
        assertEquals(MediaTaskStatus.DEAD, node("ASR").getStatus()); assertEquals(MediaTaskStatus.BLOCKED, node("TAG").getStatus());
        succeed("OCR"); succeed("COVER");
        assertEquals(PipelineStatus.WAITING_ARTIFACTS, tasks.view(100L).status());
        String preserved = node("OCR").getResultJson();
        assertTrue(tasks.retry(100L));
        assertEquals(4, node("ASR").getAttempt());
        assertEquals(preserved, node("OCR").getResultJson());
        assertEquals(5, claim(node("ASR")).getAttempt());
        assertEquals(PipelineStatus.PROCESSING, tasks.view(100L).status());
        var failed = jdbc.queryForList("SELECT payload FROM pipeline_event_outbox WHERE aggregate_id=? ORDER BY event_sequence", String.class, node("ASR").getId().toString())
                .stream().map(cn.diaojiahong.myyoutube.common.media.StructuredJson::object)
                .filter(e -> "ASR_FAILED".equals(e.getAsJsonObject("event").get("code").getAsString())).toList();
        assertEquals(4, failed.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, failed.get(i).get("attempt").getAsInt());
            assertEquals(i < 3, failed.get(i).get("retryable").getAsBoolean());
            assertEquals(i == 3, failed.get(i).get("terminal").getAsBoolean());
            assertEquals(i < 3, failed.get(i).has("nextRetryAt"));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox WHERE aggregate_id=? AND payload LIKE '%ASR_RETRY_REQUESTED%'", Integer.class, node("ASR").getId().toString()));
    }
    @Test void eventsFollowCommittedTransitionsAndIgnoreHeartbeatDuplicateClaimAndLateResult() {
        observations.getRecording().setEnabled(true);
        tasks.start(input());
        var asr = node("ASR");
        var execution = claim(asr);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class);
        assertNull(claim(asr)); tasks.heartbeat(asr.getId(), execution.getAttempt());
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        tasks.completeAndAdvance(execution, result("ASR"), null);
        count = jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class);
        tasks.completeAndAdvance(execution, null, "late failure");
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        assertEquals(List.of(1L, 2L, 3L), jdbc.queryForList("SELECT event_sequence FROM pipeline_event_outbox WHERE aggregate_id=? ORDER BY event_sequence", Long.class, asr.getId().toString()));
        assertEquals(3L, node("ASR").getEventSequence());
    }
    @Test void outboxInsertFailureRollsBackActualClaimAndAttempt() {
        observations.getRecording().setEnabled(true); tasks.start(input());
        var before = node("ASR");
        jdbc.execute("DROP TABLE pipeline_event_outbox");
        assertThrows(RuntimeException.class, () -> claim(before));
        assertEquals(MediaTaskStatus.READY, node("ASR").getStatus());
        assertEquals(before.getEventSequence(), node("ASR").getEventSequence());
        assertEquals(0, node("ASR").getAttempt());
    }
    @Test void outerRollbackRemovesPipelineNodesSequencesAndOutboxTogether() {
        observations.getRecording().setEnabled(true);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            tasks.start(input()); throw new IllegalStateException("rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        assertNull(tasks.view(100L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_task", Integer.class));
    }
    @Test void recoveringModelDefersWithoutConsumingExecutionRetriesAndKeepsDeadline() {
        observations.getRecording().setEnabled(true);
        tasks.start(input());
        var unavailable = new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_RECOVERING", 30, null);
        var first = claim(node("OCR"));
        tasks.deferAndAdvance(first, unavailable);
        var deferred = node("OCR");
        assertEquals(MediaTaskStatus.RETRY_WAIT, deferred.getStatus());
        assertEquals(0, deferred.getRetryCount());
        assertNotNull(deferred.getUnavailableDeadline());
        assertTrue(deferred.getNextRetryAt().isBefore(deferred.getUnavailableDeadline()));
        assertEquals(PipelineStatus.PROCESSING, tasks.view(100L).status());
        assertNull(claim(deferred));
        jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), deferred.getId());
        tasks.recover();
        var second = claim(node("OCR"));
        assertEquals(first.getAttempt() + 1, second.getAttempt());
        tasks.deferAndAdvance(second, unavailable);
        assertEquals(deferred.getUnavailableDeadline(), node("OCR").getUnavailableDeadline());
        assertEquals(0, node("OCR").getRetryCount());
        tasks.completeAndAdvance(first, result("OCR"), null);
        assertEquals(MediaTaskStatus.RETRY_WAIT, node("OCR").getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox WHERE aggregate_id=? AND payload LIKE '%OCR_DEFERRED%'", Integer.class, deferred.getId().toString()));
        String event = jdbc.queryForList("SELECT payload FROM pipeline_event_outbox WHERE aggregate_id=? AND payload LIKE '%OCR_DEFERRED%' ORDER BY event_sequence",
                String.class, deferred.getId().toString()).get(0);
        assertEquals("OCR_DEFERRED", cn.diaojiahong.myyoutube.common.observability.PipelineEventJson.decode(event).event().code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox WHERE aggregate_id=? AND payload LIKE '%OCR_FAILED%'", Integer.class, deferred.getId().toString()));
        jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), deferred.getId());
        tasks.recover();
        tasks.completeAndAdvance(claim(node("OCR")), result("OCR"), null);
        assertEquals(MediaTaskStatus.SUCCESS, node("OCR").getStatus());
        assertNull(node("OCR").getUnavailableDeadline());
        assertNull(node("OCR").getNextRetryAt());
    }
    @Test void prolongedModelUnavailabilityExpiresWithoutAnotherCallAndManualRetryResetsBudget() {
        observations.getRecording().setEnabled(true);
        tasks.start(input());
        var execution = claim(node("OCR"));
        tasks.deferAndAdvance(execution, new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_QUEUE_FULL", 30, null));
        jdbc.update("UPDATE media_task SET next_retry_at=?,unavailable_deadline=? WHERE id=?", past(1), past(1), execution.getTaskId());
        tasks.recover();
        assertEquals(MediaTaskStatus.DEAD, node("OCR").getStatus());
        assertEquals(0, node("OCR").getRetryCount());
        assertEquals("MODEL_UNAVAILABLE_TIMEOUT", node("OCR").getErrorMessage());
        assertNull(node("OCR").getNextRetryAt());
        succeed("ASR"); succeed("COVER");
        assertEquals(PipelineStatus.WAITING_ARTIFACTS, tasks.view(100L).status());
        tasks.retry(100L);
        assertEquals(MediaTaskStatus.READY, node("OCR").getStatus());
        assertNull(node("OCR").getUnavailableDeadline());
    }
    @Test void executionFailureAfterDeferralStillConsumesNormalRetryBudget() {
        tasks.start(input());
        tasks.deferAndAdvance(claim(node("ASR")), new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_RECOVERING", 30, null));
        jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), node("ASR").getId());
        tasks.recover();
        tasks.completeAndAdvance(claim(node("ASR")), null, "actual inference timeout");
        assertEquals(1, node("ASR").getRetryCount());
        assertEquals(MediaTaskStatus.RETRY_WAIT, node("ASR").getStatus());
    }
    @ParameterizedTest
    @ValueSource(strings = {"MODEL_RECOVERING", "MODEL_QUEUE_FULL", "MODEL_QUEUE_TIMEOUT",
            "MODEL_SHUTTING_DOWN", "MODEL_ADMISSION_BLOCKED", "MODEL_CIRCUIT_OPEN", "MODEL_RECOVERY_EXHAUSTED",
            "MODEL_EXECUTION_FAILED", "UNKNOWN"})
    void decodedHttpFailuresPersistTheExistingRetryPolicyAndAttemptFence(String reason) throws Exception {
        tasks.start(input());
        for (String type : List.of("COVER", "ASR", "OCR", "SUMMARY")) succeed(type);
        String cover = node("COVER").getResultJson();
        boolean execution = reason.equals("MODEL_EXECUTION_FAILED") || reason.equals("UNKNOWN");
        boolean terminal = reason.equals("MODEL_CIRCUIT_OPEN") || reason.equals("MODEL_RECOVERY_EXHAUSTED");
        int status = reason.equals("MODEL_EXECUTION_FAILED") ? 502 : 503;
        int code = reason.equals("UNKNOWN") ? 50301 : status == 502 ? 50202 : 50302;
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/content-pipeline/tags", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"code\":" + code + ",\"message\":\"模型故障\",\"data\":{\"resource\":\"LLM\",\"reason\":\""
                    + reason + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "30");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.start();
        var port = Feign.builder().contract(new SpringMvcContract())
                .encoder(new SpringEncoder(
                        () -> new HttpMessageConverters()))
                .errorDecoder(new ModelErrorDecoder(
                        ModelErrorDecoder.Protocol.INTERNAL))
                .retryer(Retryer.NEVER_RETRY).target(ContentPipelinePort.class,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/content-pipeline");
        var executor = mock(MediaTaskExecutor.class);
        when(executor.type()).thenReturn(MediaTaskType.TAG);
        when(executor.execute(any())).thenAnswer(call -> {
            port.generateTags(new TagRequest("title", "description", "summary"));
            return "{\"tags\":[]}";
        });
        var worker = new MediaTaskWorker(tasks, List.of(executor));
        try {
            var tag = node("TAG");
            var message = MediaTaskMessage.builder().taskId(tag.getId()).pipelineId(100L).phase("CONTENT")
                    .attempt(tag.getAttempt()).build();
            worker.execute(message);
            var failed = node("TAG");
            assertEquals(1, calls.get());
            assertEquals(terminal ? MediaTaskStatus.DEAD : MediaTaskStatus.RETRY_WAIT, failed.getStatus());
            assertEquals(execution ? 1 : 0, failed.getRetryCount());
            assertEquals(execution ? "TASK_EXECUTION_FAILED" : reason, failed.getFailureCode());
            assertEquals(cover, node("COVER").getResultJson());
            assertNull(failed.getResultJson());
            int firstAttempt = failed.getAttempt();
            tasks.completeAndAdvance(MediaTaskMessage.builder().taskId(tag.getId()).pipelineId(100L).phase("CONTENT")
                    .attempt(firstAttempt).build(), "{\"tags\":[\"late\"]}", null);
            assertEquals(failed.getStatus(), node("TAG").getStatus());
            if (terminal) {
                assertNull(failed.getNextRetryAt());
                tasks.recover(); worker.execute(message);
                assertEquals(1, calls.get());
                assertEquals(PipelineStatus.WAITING_ARTIFACTS, tasks.view(100L).status());
            } else {
                if (!execution) {
                    assertNotNull(failed.getUnavailableDeadline());
                    long delay = java.time.Duration.between(failed.getUpdateTime(), failed.getNextRetryAt()).getSeconds();
                    assertTrue(delay >= 30 && delay <= 35);
                    assertFalse(failed.getNextRetryAt().isAfter(failed.getUnavailableDeadline()));
                }
                jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), tag.getId());
                tasks.recover();
                worker.execute(MediaTaskMessage.builder().taskId(tag.getId()).pipelineId(100L).phase("CONTENT")
                        .attempt(node("TAG").getAttempt()).build());
                assertEquals(2, calls.get());
                assertEquals(firstAttempt + 1, node("TAG").getAttempt());
                assertEquals(execution ? 2 : 0, node("TAG").getRetryCount());
                assertEquals(failed.getUnavailableDeadline(), node("TAG").getUnavailableDeadline());
            }
            if (terminal) tasks.retry(100L);
            else {
                jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), tag.getId());
                tasks.recover();
            }
            var resumed = claim(node("TAG"));
            assertNotNull(resumed);
            assertTrue(resumed.getAttempt() > firstAttempt);
            tasks.completeAndAdvance(MediaTaskMessage.builder().taskId(tag.getId()).pipelineId(100L).phase("CONTENT")
                    .attempt(firstAttempt).build(), "{\"tags\":[\"late\"]}", null);
            assertEquals(MediaTaskStatus.RUNNING, node("TAG").getStatus());
            assertNull(node("TAG").getResultJson());
            tasks.completeAndAdvance(resumed, "{\"tags\":[\"recovered\"]}", null);
            assertEquals(PipelineStatus.COMPLETED, tasks.view(100L).status());
            assertEquals(cover, node("COVER").getResultJson());
            assertEquals("{\"tags\":[\"recovered\"]}", node("TAG").getResultJson());
            assertNull(node("TAG").getUnavailableDeadline());
        } finally { worker.close(); server.stop(0); }
    }
    @Test void delayedDispatchCannotExecuteAfterUnavailableDeadline() {
        observations.getRecording().setEnabled(true);
        tasks.start(input());
        tasks.deferAndAdvance(claim(node("ASR")), new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_RECOVERING", 30, null));
        jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), node("ASR").getId());
        tasks.recover();
        assertEquals(MediaTaskStatus.READY, node("ASR").getStatus());
        jdbc.update("UPDATE media_task SET unavailable_deadline=? WHERE id=?", past(1), node("ASR").getId());
        assertNull(claim(node("ASR")));
        assertEquals(MediaTaskStatus.DEAD, node("ASR").getStatus());
        assertEquals(1, node("ASR").getAttempt());
    }
    @Test void deferredTransitionRollsBackWhenOutboxCannotBeRecorded() {
        observations.getRecording().setEnabled(true);
        tasks.start(input());
        var execution = claim(node("ASR"));
        jdbc.execute("DROP TABLE pipeline_event_outbox");
        assertThrows(RuntimeException.class, () -> tasks.deferAndAdvance(execution,
                new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_RECOVERING", 30, null)));
        assertEquals(MediaTaskStatus.RUNNING, node("ASR").getStatus());
        assertNull(node("ASR").getUnavailableDeadline());
    }
    @Test void concurrentCompletionsUnlockSummaryWithDurableDependencyPointers() {
        tasks.start(input());
        var a=claim(node("ASR")); var b=claim(node("OCR"));
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> tasks.completeAndAdvance(a,result("ASR"),null)),
                CompletableFuture.runAsync(() -> tasks.completeAndAdvance(b,result("OCR"),null))).join();
        assertEquals(MediaTaskStatus.READY,node("SUMMARY").getStatus());
        assertEquals(Set.of("ASR","OCR"),claim(node("SUMMARY")).getDependencies().keySet());
        tasks.completeAndAdvance(a,result("ASR"),null);
        assertEquals(MediaTaskStatus.RUNNING,node("SUMMARY").getStatus());
    }
    @Test void lostCompletionNotificationRecoversWithoutReexecutingNodes() {
        tasks.start(input());
        doThrow(new IllegalStateException("MQ down")).when(dispatcher).completed(any());
        complete();
        assertEquals(PipelineStatus.COMPLETED,tasks.view(100L).status());
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
        assertEquals(PipelineStatus.COMPLETED,tasks.view(100L).status());
        assertEquals(MediaTaskStatus.RETRY_WAIT,tasks.listPipeline(100L,"REVIEW").get(0).getStatus());
    }
    @Test void malformedReviewOutputRemainsVisibleThroughRetriesAndExhaustion() {
        tasks.start(input()); complete(); tasks.startReview(100L);
        var artifacts = mock(cn.diaojiahong.myyoutube.common.media.ContentArtifactStore.class);
        when(artifacts.read("segments", cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Transcript.class))
                .thenReturn(new cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Transcript(List.of()));
        when(artifacts.read("OCR", cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Ocr.class))
                .thenReturn(new cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Ocr(List.of()));
        when(artifacts.read("SUMMARY", cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Summary.class))
                .thenReturn(new cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Summary("摘要", List.of()));
        when(video.reviewSpec(eq(100L), anyInt())).thenReturn(new ReviewSpec(true,"test","v1","rules",.2,.9,12000,false,4));
        var llm = mock(cn.diaojiahong.myyoutube.llm.api.LlmPort.class);
        when(llm.chat(any())).thenReturn(new cn.diaojiahong.myyoutube.llm.api.LlmPort.ChatResponse(
                "{\"summary\":\"unterminated", "test", "stop", null));
        var guard = new cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new org.springframework.mock.env.MockEnvironment());
        var executor = new cn.diaojiahong.myyoutube.transcode.task.ReviewTaskExecutor(video, artifacts,
                mock(cn.diaojiahong.myyoutube.common.utils.S3StorageUtil.class), llm, guard);
        var query = new MediaRetryService(taskRows, tasks, null, mock(RetryOwnerChecks.class), transaction, null, new PipelineProperties());
        var request = new cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Query(null,null,"REVIEW",null,1L,100L,null,20,null);
        var id = tasks.listPipeline(100L,"REVIEW").get(0).getId();
        var worker = new MediaTaskWorker(tasks, List.of(executor));
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                var before = taskRows.selectById(id);
                worker.execute(MediaTaskMessage.builder().taskId(id).pipelineId(100L).phase("REVIEW").attempt(before.getAttempt()).build());
                var row = taskRows.selectById(id);
                assertEquals(attempt, row.getAttempt());
                assertEquals("LLM", row.getDependencyService());
                assertEquals("TASK_EXECUTION_FAILED", row.getFailureCode());
                assertTrue(row.getErrorMessage().contains("JSON"));
                assertEquals(PipelineStatus.COMPLETED, tasks.view(100L).status());
                var visible = query.query(request).items();
                assertEquals(List.of(id), visible.stream().map(cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Task::taskId).toList());
                assertEquals(row.getNextRetryAt(), visible.get(0).nextRetryAt());
                if (attempt < 4) {
                    assertEquals(MediaTaskStatus.RETRY_WAIT, row.getStatus());
                    assertNotNull(row.getNextRetryAt());
                    assertFalse(visible.get(0).retryable());
                    assertEquals("AUTOMATIC_RETRY_PENDING", visible.get(0).cannotRetryReason());
                    assertNull(claim(row));
                    jdbc.update("UPDATE media_task SET next_retry_at=? WHERE id=?", past(1), id);
                    tasks.recover();
                    assertTrue(query.query(request).items().isEmpty());
                    var all = new cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Query(null,null,"REVIEW","ALL",1L,100L,null,20,null);
                    assertEquals(MediaTaskStatus.READY, query.query(all).items().get(0).status());
                } else {
                    assertEquals(MediaTaskStatus.DEAD, row.getStatus());
                    assertNull(row.getNextRetryAt());
                }
            }
            var results = org.mockito.ArgumentCaptor.forClass(ReviewResult.class);
            verify(dispatcher, times(4)).reviewed(results.capture());
            for (int i = 0; i < 4; i++) {
                assertEquals(i + 1, results.getAllValues().get(i).attempt());
                assertEquals(i == 3, results.getAllValues().get(i).exhausted());
                assertNotNull(results.getAllValues().get(i).error());
                assertNull(results.getAllValues().get(i).verdictJson());
            }
        } finally { worker.close(); }
    }

    @Test void staleWorkerLeaseIsRetriedAndLateResultIgnored() {
        tasks.start(input()); var old=claim(node("ASR"));
        jdbc.update("UPDATE media_task SET update_time=? WHERE id=?",past(600),old.getTaskId());
        tasks.recover(); assertEquals(MediaTaskStatus.RETRY_WAIT,node("ASR").getStatus());
        tasks.completeAndAdvance(old,result("ASR"),null);
        assertEquals(MediaTaskStatus.RETRY_WAIT,node("ASR").getStatus());
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
        assertEquals(MediaTaskStatus.RUNNING,node("TAG").getStatus());
        assertEquals(PipelineStatus.PROCESSING,tasks.view(100L).status());
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
        assertEquals(MediaTaskStatus.RETRY_WAIT, node("OCR").getStatus());
        assertEquals(MediaTaskStatus.SUCCESS, node("COVER").getStatus());
        assertEquals(PipelineStatus.PROCESSING, tasks.view(100L).status());
        verify(dispatcher, never()).draftFinished(anyLong(), anyBoolean(), any(), any());
    }
    @Test void lostReadyDispatchIsRecoveredAndDuplicateDispatchCannotReexecute() {
        doThrow(new IllegalStateException("MQ down")).when(dispatcher).dispatch(any()); tasks.start(input());
        assertEquals(MediaTaskStatus.READY,node("ASR").getStatus());
        doNothing().when(dispatcher).dispatch(any());
        jdbc.update("UPDATE media_task SET dispatch_after=? WHERE status='READY'", past(1));
        tasks.recover(); verify(dispatcher,times(6)).dispatch(any());
        var candidate=node("ASR"); assertNotNull(claim(candidate)); assertNull(claim(candidate));
        assertEquals(1,metrics.get("media.claim.skipped").tag("phase","CONTENT").counter().count());
        System.out.println("Lost dispatch recovery: READY messages=3 original+3 recovered; duplicate claims=1; duplicate executions=0");
    }
    @Test void unexplainedPendingAndUnknownStatusAreOrchestrationErrors() {
        assertThrows(IllegalStateException.class,()->PipelineState.derive(List.of(new MediaTask().setStatus(MediaTaskStatus.PENDING))));
        assertThrows(IllegalStateException.class,()->PipelineState.derive(List.of(new MediaTask())));
    }
    @Test void delayedReviewRequestAfterDisableCannotCreateReviewExecution() {
        tasks.start(input()); complete();
        when(video.reviewSpec(100L,0)).thenReturn(new ReviewSpec(false,"test","v1","rules",0.2,0.9,12000,true,4));
        tasks.startReview(100L); assertTrue(tasks.listPipeline(100L,"REVIEW").isEmpty());
    }
}
