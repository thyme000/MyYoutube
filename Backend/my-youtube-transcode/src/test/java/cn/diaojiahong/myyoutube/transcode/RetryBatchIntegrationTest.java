package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.observability.PipelineEvent;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Retryer;
import java.io.OutputStream;
import org.redisson.api.RBucket;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.handler.UnexpectedExceptionHandler;
import cn.diaojiahong.myyoutube.common.feign.FeignExceptionHandler;
import cn.diaojiahong.myyoutube.common.feign.InternalApiErrorDecoder;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.transcode.controller.InternalMediaRetryController;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaRetryBatchMapper;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import cn.diaojiahong.myyoutube.transcode.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(
        named="retry.redis.address",
        matches="redis://127[.]0[.]0[.]1:[0-9]+")
@SpringJUnitConfig({MediaTaskIntegrationTest.Config.class,RetryBatchIntegrationTest.Config.class})
class RetryBatchIntegrationTest {
    @Configuration static class Config {
        @Bean
        PipelineProperties budgets() {
            return new PipelineProperties();
        }

        @Bean
        RetryOwnerChecks owners() {
            return mock(RetryOwnerChecks.class);
        }

        @Bean(destroyMethod="shutdown")
        RedissonClient redis() {
            return redisClient();
        }

        @Bean
        RetryBatchOccupation occupation(RedissonClient redis, PipelineProperties props) {
            return new RetryBatchOccupation(redis,props);
        }

        @Bean
        RetryBatchStore batches(
                MediaRetryBatchMapper batches,
                MediaTaskMapper tasks,
                RetryBatchOccupation occupation,
                TransactionTemplate tx) {
            return new RetryBatchStore(batches,tasks,occupation,tx);
        }

        @Bean
        MediaRetryService retries(
                MediaTaskMapper rows,
                MediaTaskService tasks,
                RetryBatchStore store,
                RetryOwnerChecks owners,
                TransactionTemplate tx,RetryBatchOccupation occupation,PipelineProperties props) {
            return new MediaRetryService(rows,tasks,store,owners,tx,occupation,props);
        }
    }

    static RedissonClient redisClient() {
        var config=new org.redisson.config.Config();
        config.setThreads(2);
        config.setNettyThreads(2);
        config.useSingleServer()
                .setAddress(System.getProperty("retry.redis.address"))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        return Redisson.create(config);
    }

    @Autowired PipelineProperties budgets;
    @Autowired RedissonClient redis;
    @Autowired RetryBatchOccupation occupation;
    @Autowired MediaRetryBatchMapper batchRows;
    @Autowired MediaTaskService tasks;
    @Autowired MediaTaskMapper rows;
    @Autowired MediaTaskDispatcher dispatcher;
    @Autowired RetryBatchStore batches;
    @Autowired MediaRetryService retries;
    @Autowired RetryOwnerChecks owners;
    @Autowired TransactionTemplate tx;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    @Autowired DataSource ds;
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() throws Exception {
        reset(dispatcher,owners);
        budgets.setRetryOccupationSeconds(3600);
        observations.getRecording().setEnabled(false);
        jdbc=new JdbcTemplate(ds);
        redis.getKeys().delete("media:retry:active","media:retry:admission");
        for (String table:List.of("media_retry_batch","media_task","media_pipeline"))
            jdbc.execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(ds);
        OutboxTestDatabase.reset(jdbc);
    }

    Input input(long pipeline,long content) {
        return new Input(pipeline,content,9L,"title","description","",null,
                new ContentMedia("video",new VideoMedia("video.mp4",1000),null),1700000000000L);
    }

    MediaTask node(String type) {
        return tasks.listPipeline(100L,"CONTENT")
                .stream()
                .filter(t -> type.equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
    }

    MediaTaskMessage claim(MediaTask row) {
        return tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).pipelineId(row.getPipelineId()).phase(row.getPhase()).attempt(row.getAttempt()).build());
    }

    void succeed(String type) {
        String result=switch(type) {
            case "COVER" -> "{\"coverObjectKey\":\"cover\"}";
            case "ASR" -> "{\"vttKey\":\"vtt\",\"segmentsKey\":\"segments\"}";
            default -> "{\"artifactKey\":\"artifact\"}";
        };
        tasks.completeAndAdvance(Objects.requireNonNull(claim(node(type))),result,null);
    }

    void failedPipeline() {
        tasks.start(input(100,1));
        succeed("COVER");
        for (String type:List.of("ASR","OCR")) tasks.deferAndAdvance(claim(node(type)),new ModelUnavailable(type,"MODEL_CIRCUIT_OPEN",30,null));
        assertEquals(PipelineStatus.WAITING_ARTIFACTS,tasks.view(100L).status());
    }

    Admission create(String request,String... types) {
        return retries.create(new Request(request,Arrays.stream(types).map(t -> node(t).getId()).toList()),1L);
    }

    @Test
    void internalHttpKeepsPersistedAdmissionDecisionsAndIdempotency() throws Exception {
        failedPipeline();
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        var converter = new MappingJackson2HttpMessageConverter(builder.build());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new InternalMediaRetryController(retries, batches))
                .setMessageConverters(converter).setControllerAdvice(
                        new GlobalExceptionHandler(),
                        new FeignExceptionHandler(),
                        new UnexpectedExceptionHandler()).build();
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/media-retries/batches", exchange -> {
            calls.incrementAndGet();
            try {
                MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.post(exchange.getRequestURI()).contentType("application/json")
                                .content(exchange.getRequestBody().readAllBytes())).andReturn().getResponse();
                byte[] body = response.getContentAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.getStatus(), body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (Exception error) {
                throw new java.io.IOException(error);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ObjectFactory<HttpMessageConverters> converters =
                    () -> new HttpMessageConverters(converter);
            MediaRetryPort port = Feign.builder().contract(new SpringMvcContract())
                    .encoder(new SpringEncoder(converters))
                    .decoder(new SpringDecoder(converters))
                    .errorDecoder(new InternalApiErrorDecoder()).retryer(Retryer.NEVER_RETRY)
                    .target(MediaRetryPort.class,
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/media-retries");
            var request = new Request("http-created", List.of(node("ASR").getId()));
            MediaRetryPort.Admission accepted = port.create(request, 1L);
            assertTrue(accepted.accepted());
            assertEquals("RETRY_BATCH_CREATED", accepted.code());
            assertEquals(batches.current().batchId(), accepted.batch().batchId());
            MediaRetryPort.Admission busy = port.create(new Request("http-busy", List.of(node("OCR").getId())), 1L);
            assertFalse(busy.accepted());
            assertEquals("RETRY_BATCH_RUNNING", busy.code());
            assertEquals(accepted.batch().batchId(), busy.batch().batchId());
            assertEquals(accepted.batch().batchId(), port.create(request, 1L).batch().batchId());
            BizException conflict = assertThrows(BizException.class,
                    () -> port.create(new Request("http-created", List.of(node("OCR").getId())), 1L));
            assertEquals(ErrorCode.STATE_CONFLICT, conflict.getErrorCode());
            assertEquals(4, calls.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_retry_batch", Integer.class));
            assertEquals(MediaTaskStatus.SUCCESS,node("COVER").getStatus());
            assertEquals(MediaTaskStatus.DEAD,node("OCR").getStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentAdmissionsHaveOneWinnerAndIdempotentReplay() {
        failedPipeline();
        CompletableFuture<MediaRetryPort.Admission> a=CompletableFuture.supplyAsync(() -> create("one","ASR"));
        RedissonClient otherRedis=redisClient();
        try {
        var otherOccupation=new RetryBatchOccupation(otherRedis,new PipelineProperties());
        var otherStore=new RetryBatchStore(batchRows,rows,otherOccupation,tx);
        var otherService=new MediaRetryService(
                rows,
                tasks,
                otherStore,
                owners,
                tx,
                otherOccupation,
                new PipelineProperties());
        CompletableFuture<MediaRetryPort.Admission> b=CompletableFuture.supplyAsync(() -> otherService.create(
                new Request("two",List.of(node("OCR").getId())),
                1L));
        MediaRetryPort.Admission first=a.join();
        MediaRetryPort.Admission second=b.join();
        assertNotEquals(first.accepted(),second.accepted());
        MediaRetryPort.Admission winner=first.accepted() ? first : second;
        assertEquals(winner.batch().batchId(),batches.current().batchId());
        assertEquals("RETRY_BATCH_RUNNING",(first.accepted() ? second : first).code());
        assertEquals(
                winner.batch().batchId(),
                create(first.accepted() ? "one":"two",first.accepted() ? "ASR":"OCR").batch().batchId());
        assertThrows(BizException.class,() -> create(first.accepted() ? "one":"two","SUMMARY"));
        } finally {
            otherRedis.shutdown();
        }
    }

    @Test
    void selectedBranchPreservesSuccessAndLeavesIndependentDeadBranch() {
        failedPipeline();
        MediaRetryPort.Admission selected=create("asr-only","ASR");
        assertEquals(MediaTaskStatus.READY,node("ASR").getStatus());
        assertEquals(MediaTaskStatus.DEAD,node("OCR").getStatus());
        assertEquals(MediaTaskStatus.SUCCESS,node("COVER").getStatus());
        succeed("ASR");
        assertNull(batches.current());
        assertEquals(MediaRetryBatchStatus.COMPLETED,batches.batch(selected.batch().batchId()).status());
        assertEquals(PipelineStatus.WAITING_ARTIFACTS,tasks.view(100L).status());
        assertEquals(2,node("ASR").getAttempt());
    }

    @Test
    void downstreamSharesBudgetAndBatchRemainsUntilTagCompletes() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("both","ASR","OCR").batch();
        MediaTaskMessage asr=claim(node("ASR"));
        assertNotNull(asr);
        assertNull(claim(node("OCR")));
        tasks.completeAndAdvance(asr,"{\"vttKey\":\"vtt\",\"segmentsKey\":\"segments\"}",null);
        succeed("OCR");
        assertNotNull(batches.current());
        assertEquals(batch.batchId(),node("SUMMARY").getRetryBatchId());
        succeed("SUMMARY");
        assertNotNull(batches.current());
        succeed("TAG");
        assertNull(batches.current());
        assertEquals(PipelineStatus.COMPLETED,tasks.view(100L).status());
        assertEquals(4,batches.batch(batch.batchId()).affectedTasks());
        assertEquals(4,batches.affected(batch.batchId()).size());
    }

    @Test
    void normalReadyTasksTakePriorityAndUserSubmissionIsNotRejected() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("priority","ASR").batch();
        tasks.start(input(200,2));
        assertNull(claim(node("ASR")));
        assertEquals("NORMAL_TASKS_FIRST",batches.batch(batch.batchId()).waitReason());
        assertEquals(PipelineStatus.PROCESSING,tasks.view(200L).status());
        assertEquals(MediaTaskStatus.READY,node("ASR").getStatus());
    }

    @Test
    void blockedSelectionDoesNotSilentlyResumeUnselectedUpstream() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("blocked","SUMMARY").batch();
        assertNull(batches.current());
        assertEquals(MediaRetryItemState.SKIPPED,batches.items(batch.batchId()).get(0).state());
        assertEquals("DEPENDENCY_NOT_SELECTED",batches.items(batch.batchId()).get(0).reason());
        assertEquals(MediaTaskStatus.DEAD,node("ASR").getStatus());
    }

    @Test
    void ownerDeletionAfterSelectionSkipsAndReleasesBatch() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("deleted","ASR").batch();
        when(owners.reason(any())).thenReturn("OWNER_DELETED");
        assertNull(claim(node("ASR")));
        assertNull(batches.current());
        assertEquals(MediaRetryItemState.SKIPPED,batches.items(batch.batchId()).get(0).state());
        assertEquals(MediaTaskStatus.BLOCKED,node("ASR").getStatus());
    }

    @Test
    void transientOwnerCheckFailureRejectsWithoutPersistingAQueuedBatch() {
        failedPipeline();
        when(owners.reason(any())).thenThrow(new IllegalStateException("video down"));
        assertThrows(IllegalStateException.class,() -> create("owner-down","ASR"));
        assertNull(batches.current());
        assertEquals(0,batchRows.selectCount(null));
        reset(owners);
        assertEquals(MediaRetryBatchStatus.RUNNING,create("owner-down","ASR").batch().status());
    }

    @Test
    void batchHistorySurvivesRetryingSameTaskInLaterBatch() {
        failedPipeline();
        MediaRetryPort.Batch old=create("old","ASR").batch();
        tasks.deferAndAdvance(claim(node("ASR")),new ModelUnavailable("ASR","MODEL_CIRCUIT_OPEN",30,null));
        assertEquals(MediaRetryBatchStatus.COMPLETED_WITH_FAILURES,batches.batch(old.batchId()).status());
        MediaRetryPort.Batch next=create("new","ASR").batch();
        succeed("ASR");
        assertEquals(1,batches.batch(old.batchId()).affectedTasks());
        assertEquals(MediaRetryItemState.FAILED,batches.items(old.batchId()).get(0).state());
        assertEquals(2,batches.items(old.batchId()).get(0).executionAttempt());
        assertEquals(3,batches.items(next.batchId()).get(0).executionAttempt());
    }

    @Test
    void queryFindsAllTypesAndBlockedDependencyResources() {
        failedPipeline();
        CursorPage<MediaRetryPort.Task,Long> all=retries.query(new Query(null,null,null,null,null,null,null,100,null));
        assertEquals(4,all.items().size());
        CursorPage<MediaRetryPort.Task,Long> asr=retries.query(new Query(null,"ASR",null,null,null,null,null,100,null));
        assertTrue(asr.items().stream().anyMatch(t -> "SUMMARY".equals(t.taskType())));
        assertFalse(asr.items().stream().anyMatch(t -> "OCR".equals(t.taskType())));
        assertThrows(
                BizException.class,
                () -> retries.query(new Query("unknown",null,null,null,null,null,null,10,null)));
    }

    @Test
    void outboxFailureRollsBackBatchAndGlobalOccupation() {
        failedPipeline();
        observations.getRecording().setEnabled(true);
        jdbc.execute("DROP TABLE pipeline_event_outbox");
        assertThrows(RuntimeException.class,() -> create("rollback","ASR"));
        assertNull(batches.current());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_retry_batch",Integer.class));
    }

    @Test
    void batchEventsRoundTripAndRemainInBusinessTransaction() {
        failedPipeline();
        observations.getRecording().setEnabled(true);
        MediaRetryPort.Batch batch=create("events","ASR").batch();
        succeed("ASR");
        List<PipelineEvent> events=jdbc.queryForList(
                "SELECT payload FROM pipeline_event_outbox WHERE aggregate_type='RETRY_BATCH' ORDER BY event_sequence",
                String.class)
                .stream().map(cn.diaojiahong.myyoutube.common.observability.PipelineEventJson::decode).toList();
        assertEquals(
                List.of("RETRY_BATCH_CREATED","RETRY_BATCH_STARTED","RETRY_BATCH_COMPLETED"),
                events.stream().map(e -> e.event().code()).toList());
        assertTrue(events.stream().allMatch(e -> e.aggregate().id().equals(batch.batchId().toString())));
    }

    @Test
    void acceptedTasksUseExistingRecoveryWithoutABatchScheduler() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("restart","ASR").batch();
        clearInvocations(dispatcher);
        jdbc.update(
                "UPDATE media_task SET dispatch_after=? WHERE task_type='ASR'",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        MediaTaskIntegrationTest.SqlProbe.SQL.get().clear();
        tasks.recover();
        assertTrue(MediaTaskIntegrationTest.SqlProbe.SQL.get()
                .stream()
                .noneMatch(sql -> sql.contains("media_retry_batch")));
        verify(dispatcher).dispatch(argThat(message -> batch.batchId().equals(message.getRetryBatchId())));
        succeed("ASR");
        assertNull(batches.current());
    }

    @Test
    void ownerDisappearingBeforeFirstDownstreamAttemptIsSkippedWithValidOutbox() {
        failedPipeline();
        observations.getRecording().setEnabled(true);
        create("deleted-after-resume","ASR","OCR");
        succeed("ASR");
        succeed("OCR");
        assertEquals(0,node("SUMMARY").getAttempt());
        when(owners.reason(any())).thenReturn("OWNER_DELETED");
        assertNull(claim(node("SUMMARY")));
        assertEquals(MediaTaskStatus.BLOCKED,node("SUMMARY").getStatus());
        assertEquals("RETRY_SKIPPED_OWNER_DELETED",node("SUMMARY").getFailureCode());
        assertNull(batches.current());
    }

    @Test
    void automaticRecoveryCannotBypassRetryConcurrency() {
        failedPipeline();
        create("recover-budget","ASR","OCR");
        MediaTaskMessage asr=claim(node("ASR"));
        jdbc.update(
                "UPDATE media_task SET dispatch_after=? WHERE task_type='OCR'",
                java.time.LocalDateTime.now().minusMinutes(1));
        tasks.recover();
        assertNull(claim(node("OCR")));
        assertEquals(MediaTaskStatus.RUNNING,node("ASR").getStatus());
        assertEquals(MediaTaskStatus.READY,node("OCR").getStatus());
    }

    @Test
    void cachedBusyAndCurrentStatusDoNotReadTheDatabase() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("cached","ASR").batch();
        MediaTaskIntegrationTest.SqlProbe.SQL.get().clear();
        assertEquals(batch.batchId(),batches.current().batchId());
        assertFalse(retries.create(new Request("other",List.of(99L)),1L).accepted());
        assertTrue(MediaTaskIntegrationTest.SqlProbe.SQL.get().isEmpty());
    }

    @Test
    void expiredOccupationCannotAdmitAnotherBatchWhileOldTasksRun() throws Exception {
        failedPipeline();
        budgets.setRetryOccupationSeconds(1);
        MediaRetryPort.Batch batch=create("ttl","ASR").batch();
        RBucket<Object> bucket=redis.getBucket("media:retry:active");
        assertTrue(bucket.remainTimeToLive()>0);
        assertNotNull(claim(node("ASR")));
        assertTrue(bucket.remainTimeToLive()>0);
        Thread.sleep(1100);
        assertNull(occupation.current());
        MediaRetryPort.Admission admission=create("after-expiry","OCR");
        assertFalse(admission.accepted());
        assertEquals(batch.batchId(),admission.batch().batchId());
        assertEquals(batch.batchId(),occupation.current().batch().batchId());
        assertEquals(1,batchRows.selectCount(null));
    }

    @Test
    void historyAccessFinishesCommittedTasksWhenCompletionCallbackWasLost() {
        failedPipeline();
        MediaRetryPort.Batch batch=create("lazy-history","ASR").batch();
        jdbc.update("UPDATE media_task SET status='SUCCESS',attempt=attempt+1 WHERE task_type='ASR'");
        assertEquals(MediaRetryBatchStatus.RUNNING,batchRows.selectById(batch.batchId()).getStatus());
        assertEquals(MediaRetryBatchStatus.COMPLETED,batches.batch(batch.batchId()).status());
        assertEquals(MediaRetryItemState.SUCCESS,batches.items(batch.batchId()).get(0).state());
        assertNotNull(batchRows.selectById(batch.batchId()).getResultJson());
        assertNull(occupation.current());
        occupation.update(batch);
        assertNull(occupation.current());
    }

    @Test
    void admissionLazilyFinishesOldBatchAfterOccupationIsLost() {
        failedPipeline();
        MediaRetryPort.Batch old=create("lazy-admission","ASR").batch();
        jdbc.update("UPDATE media_task SET status='SUCCESS',attempt=attempt+1 WHERE task_type='ASR'");
        redis.getBucket("media:retry:active").delete();
        MediaRetryPort.Admission next=create("next","OCR");
        assertTrue(next.accepted());
        assertEquals(MediaRetryBatchStatus.COMPLETED,batches.batch(old.batchId()).status());
        occupation.update(batches.batch(old.batchId()));
        assertEquals(next.batch().batchId(),occupation.current().batch().batchId());
    }

    @Test
    void simultaneousClaimsOnDifferentPipelinesShareTheBatchBudget() {
        failedPipeline();
        tasks.start(input(200,2));
        List<MediaTask> others=tasks.listPipeline(200L,"CONTENT");
        for (MediaTask row:others) if (MediaTaskStatus.READY.equals(row.getStatus()))
            jdbc.update("UPDATE media_task SET status='DEAD' WHERE id=?",row.getId());
        MediaTask other=others.stream().filter(row -> "ASR".equals(row.getTaskType())).findFirst().orElseThrow();
        retries.create(new Request("cross-pipeline",List.of(node("ASR").getId(),other.getId())),1L);
        CompletableFuture<MediaTaskMessage> a=CompletableFuture.supplyAsync(() -> claim(node("ASR")));
        CompletableFuture<MediaTaskMessage> b=CompletableFuture.supplyAsync(() -> claim(rows.selectById(other.getId())));
        assertEquals(1,(a.join()==null ? 0 : 1)+(b.join()==null ? 0 : 1));
    }

    @Test
    void invalidSelectionsAndActorDoNotCreateHistory() {
        for (MediaRetryPort.Request request:List.of(new Request("empty",List.of()),new Request("bad id",List.of(1L)),
                new Request("negative",List.of(-1L)),new Request(
                        "too-many",
                        java.util.stream.LongStream.rangeClosed(1,21).boxed().toList())))
            assertThrows(BizException.class,() -> retries.create(request,1L));
        assertThrows(BizException.class,() -> retries.create(new Request("actor",List.of(1L)),2L-2));
        assertEquals(0,batchRows.selectCount(null));
    }

}
