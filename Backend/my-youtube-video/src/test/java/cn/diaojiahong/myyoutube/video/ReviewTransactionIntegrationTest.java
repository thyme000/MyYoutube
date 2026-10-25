package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.service.*;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.common.mq.message.ContentUnderstoodMessage;
import java.util.*;
import java.time.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.recommend.auto.TagResultMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

@org.springframework.test.context.TestPropertySource(properties="my-youtube.autotag.min-confidence=0.5")
@SpringJUnitConfig(ReviewTransactionIntegrationTest.Config.class)
class ReviewTransactionIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {VideoMapper.class, ContentMapper.class})
    static class Config {
        @Bean cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings frameSettings() {
            return new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings();
        }
        @Bean DataSource dataSource() { return ContentTestDatabase.create("reviews"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds); return bean.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean IContentService contentService() { return new ContentServiceImpl(); }
        @Bean ContentPipelineProperties policy() {
            var policy = new ContentPipelineProperties();
            policy.setAutoReview(true);
            policy.setReviewVisionEnabled(true);
            policy.setReviewModel("vision-test");
            return policy;
        }
        @Bean TransactionTemplate transaction(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean PublicationEffectsService effects() { return mock(PublicationEffectsService.class); }
        @Bean TagResultMapper tagMapper() { return mock(TagResultMapper.class); }
        @Bean RocketMQTemplate mq() { return mock(RocketMQTemplate.class); }
        @Bean MediaTaskPort tasks() { return mock(MediaTaskPort.class); }
        @Bean ContentArtifactStore artifacts() { return mock(ContentArtifactStore.class); }
        @Bean ContentUnderstandingService understandingService(ContentUnderstandingMapper rows, ContentMapper videos,
                ContentReviewRecordMapper records, ContentPipelineProperties properties, MediaTaskPort tasks, ContentArtifactStore artifacts, TagResultMapper tags, RocketMQTemplate mq, TransactionTemplate tx, cn.diaojiahong.myyoutube.video.service.ContentObservation observation) {
            return new ContentUnderstandingService(rows, videos, records, properties, tasks, artifacts,
                    tags,
                    mock(cn.diaojiahong.myyoutube.recommend.service.IContentTagService.class),
                    mq, tx, new SimpleMeterRegistry(), observation);
        }
        @Bean cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations() { return new cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties(); }
        @Bean cn.diaojiahong.myyoutube.common.observability.PipelineEvents pipelineEvents(DataSource ds, DataSourceTransactionManager manager, cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties props) {
            return new cn.diaojiahong.myyoutube.common.observability.PipelineEvents(props,
                    new cn.diaojiahong.myyoutube.common.observability.PipelineEventRecorder(new JdbcTemplate(ds), props, manager), "video", java.time.Clock.systemUTC());
        }
        @Bean cn.diaojiahong.myyoutube.video.service.ContentObservation observation(ContentUnderstandingMapper rows, cn.diaojiahong.myyoutube.common.observability.PipelineEvents events) { return new cn.diaojiahong.myyoutube.video.service.ContentObservation(rows, events); }
        @Bean PromptTemplates prompts() { return new PromptTemplates(); }
        @Bean ReviewDecisionService decisions(VideoMapper videos, ContentUnderstandingMapper understanding,
                ContentReviewRecordMapper records, ContentPublicationEventMapper events, IContentService contents, ContentPipelineProperties properties, PublicationEffectsService effects, PromptTemplates prompts, cn.diaojiahong.myyoutube.video.service.ContentObservation observation) {
            return new ReviewDecisionService(understanding, records, events, contents, properties, effects, prompts, observation, frameSettings());
        }
    }
    @Autowired PromptTemplates prompts;
    @Autowired ReviewDecisionService decisions;
    @Autowired ContentUnderstandingService content;
    @Autowired MediaTaskPort tasks;
    @Autowired ContentArtifactStore artifacts;
    @Autowired ContentUnderstandingMapper understanding;
    @Autowired DataSource dataSource;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    @Autowired ContentPipelineProperties policy;
    @Autowired cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings frameSettings;
    @Autowired TransactionTemplate tx;
    @Autowired TagResultMapper tags;
    @Autowired RocketMQTemplate mq;
    JdbcTemplate jdbc;
    @BeforeEach void schema() throws Exception {
        reset(tasks, artifacts, tags, mq); observations.getRecording().setEnabled(false);
        policy.setAutoReview(true);
        policy.setReviewVisionEnabled(true);
        frameSettings.replace("interval-seconds: 5\nmax-frames: 10\nmin-success-percent: 80\ncover-max-candidates: 8\nocr-batch-size: 2\nreview-batch-size: 4");
        jdbc = new JdbcTemplate(dataSource);
        ContentTestDatabase.outbox(jdbc);
        for (String table : new String[]{"content_review_record", "content_publication_event", "content_understanding", "video", "content"}) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE video(id BIGINT PRIMARY KEY,object_key VARCHAR(512),duration_ms BIGINT)");
        jdbc.execute("CREATE TABLE content(id BIGINT PRIMARY KEY,type VARCHAR(16),author_id BIGINT,title VARCHAR(100),description TEXT,cover_object_key VARCHAR(512),category_code VARCHAR(32),tags TEXT,allow_comment INT,region VARCHAR(32),is_delete INT DEFAULT 0,status VARCHAR(16),visibility VARCHAR(16),index_version BIGINT NOT NULL DEFAULT 1,play_count BIGINT DEFAULT 0,like_count BIGINT DEFAULT 0,collect_count BIGINT DEFAULT 0,comment_count BIGINT DEFAULT 0,share_count BIGINT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(dataSource);
        jdbc.update("INSERT INTO video VALUES(1,'processed.mp4',1000)");
        jdbc.update("INSERT INTO content(id,type,author_id,title,status,visibility,is_delete) VALUES(1,'video',9,'标题','review','public',0)");
    }
    void state(ContentReviewState state) {
        understanding.insert(new ContentUnderstanding().setContentId(1L).setState(state).setReviewAttempt(1).setPipelineId(100L).setReceived(state != ContentReviewState.WAITING_PIPELINE)
                .setInputJson(JsonUtil.toJson(input())).setSubmissionAck(true).setRetryRequested(false).setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC)));
    }
    ReviewVerdict verdict(String decision) {
        return ReviewVerdict.parse("{\"decision\":\"" + decision + "\",\"riskScore\":0.1,\"confidence\":0.99,\"reason\":\"上下文安全\",\"evidence\":[]}", "vision-test");
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Test void reviewResultKeepsExecutedTemplateVersionAfterHotUpdate() throws Exception {
        state(ContentReviewState.AUTO_PENDING);
        var spec = decisions.begin(100L, 1);
        String defaults = new ClassPathResource("prompts/model-prompts.yaml").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        try {
            prompts.replace(defaults.replace("你是内容审核者", "新版审核者"));
            assertNotEquals(spec.rulesVersion(), prompts.snapshot().version());
            var verdict = new ReviewVerdict("UNCERTAIN", 0.1, 0.99, "待人工确认", new com.google.gson.JsonArray(), "test", spec.rulesVersion());
            decisions.acceptResult(new ReviewResult(100L, 1L, 88L, 1, JsonUtil.toJson(verdict), null, false));
            assertEquals(spec.rulesVersion(), jdbc.queryForObject("SELECT rules_version FROM content_review_record", String.class));
            assertEquals(ContentReviewState.MANUAL_PENDING, understanding.selectById(1L).getState());
        } finally { prompts.replace(defaults); }
    }
    @Test void concurrentMachinePassCreatesOnePublicationEvent() {
        observations.getRecording().setEnabled(true);
        state(ContentReviewState.AUTO_RUNNING);
        var a = CompletableFuture.supplyAsync(() -> decisions.machine(1L, 1, verdict("PASS")));
        var b = CompletableFuture.supplyAsync(() -> decisions.machine(1L, 1, verdict("PASS")));
        assertEquals(1, (a.join() ? 1 : 0) + (b.join() ? 1 : 0));
        assertEquals("published", jdbc.queryForObject("SELECT status FROM content WHERE id=1", String.class));
        assertEquals("published", jdbc.queryForObject("SELECT status FROM content WHERE id=1", String.class));
        assertEquals(ContentReviewState.APPROVED, understanding.selectById(1L).getState());
        assertEquals(1, count("content_review_record")); assertEquals(1, count("content_publication_event"));
        assertEquals(1, count("pipeline_event_outbox"));
        assertEquals(1L, understanding.selectById(1L).getEventSequence());
    }
    @Test void manualTakeoverRetainsMachineRecordAndRejectsLateResult() {
        observations.getRecording().setEnabled(true);
        state(ContentReviewState.AUTO_RUNNING);
        assertTrue(decisions.machine(1L, 1, verdict("UNCERTAIN")));
        assertEquals(ContentReviewState.MANUAL_PENDING, understanding.selectById(1L).getState());
        assertTrue(decisions.manual(1L, false, "人工确认违规", 7L));
        assertFalse(decisions.machine(1L, 1, verdict("PASS")));
        assertEquals("banned", jdbc.queryForObject("SELECT status FROM content WHERE id=1", String.class));
        assertEquals(2, count("content_review_record")); assertEquals(1, count("content_publication_event"));
        assertEquals(0, jdbc.queryForObject("SELECT delta FROM content_publication_event", Integer.class));
        assertEquals(List.of(1L, 2L), jdbc.queryForList("SELECT event_sequence FROM pipeline_event_outbox ORDER BY event_sequence", Long.class));
    }
    @Test void missingContentCancelsMachinePassWithoutPublishing() {
        state(ContentReviewState.AUTO_RUNNING); jdbc.update("DELETE FROM content WHERE id=1");
        assertFalse(decisions.machine(1L, 1, verdict("PASS")));
        assertEquals(ContentReviewState.CANCELLED, understanding.selectById(1L).getState());
        assertEquals(1, count("content_review_record")); assertEquals(0, count("content_publication_event"));
    }
    @Test void artifactsMustBeReadyBeforeManualApprovalAndNoLegacyBypass() {
        state(ContentReviewState.WAITING_PIPELINE);
        assertThrows(BizException.class, () -> decisions.manual(1L, true, "通过", 7L));
        assertThrows(BizException.class, () -> decisions.manual(1L, true, " ", 7L));
        understanding.deleteById(1L);
        assertThrows(BizException.class, () -> decisions.manual(1L, true, "通过", 7L));
        assertEquals(0, count("content_publication_event"));
    }
    @Test void fourFailuresTransferToManualAndOldAttemptCannotRetryIt() {
        observations.getRecording().setEnabled(true);
        state(ContentReviewState.AUTO_PENDING);
        for (int attempt=1; attempt<=4; attempt++) {
            assertTrue(decisions.begin(100L, attempt).enabled());
            decisions.acceptResult(new ReviewResult(100L,1L,88L,attempt,null,"provider unavailable",attempt==4));
        }
        assertEquals(ContentReviewState.MANUAL_PENDING, understanding.selectById(1L).getState());
        assertEquals(4, count("content_review_record"));
        decisions.acceptResult(new ReviewResult(100L,1L,88L,3,null,"late failure",false));
        assertEquals(4, count("content_review_record"));
        assertEquals(0, count("content_publication_event"));
        assertEquals(8, count("pipeline_event_outbox"));
    }
    Input input() { return new Input(100L, 1L, 9L, "标题", "简介", "", null, new cn.diaojiahong.myyoutube.common.media.ContentMedia("video", new cn.diaojiahong.myyoutube.common.media.VideoMedia("processed.mp4", 1000), null), System.currentTimeMillis()); }
    @Test void completionIsIdempotentAndAutoReviewFalseNeverCreatesReview() {
        observations.getRecording().setEnabled(true);
        policy.setAutoReview(false);
        state(ContentReviewState.WAITING_PIPELINE);
        var row = understanding.selectById(1L);
        var input = JsonUtil.fromJson(row.getInputJson(), Input.class);
        var results=Map.of("COVER","{\"coverObjectKey\":\"cover\"}","ASR","{\"vttKey\":\"captions\",\"segmentsKey\":\"transcript\"}",
                "OCR","{\"artifactKey\":\"ocr\"}","SUMMARY","{\"artifactKey\":\"summary\"}","TAG","{\"artifactKey\":\"tag\"}");
        var event=new ContentUnderstoodMessage(new Manifest(100L,1L,PipelineStatus.COMPLETED,input,results,System.currentTimeMillis()));
        when(artifacts.read("summary", ContentArtifacts.Summary.class)).thenReturn(new ContentArtifacts.Summary("",List.of()));
        when(artifacts.read("tag", com.google.gson.JsonObject.class)).thenReturn(StructuredJson.object("{\"category\":\"OTHER\",\"tags\":[]}"));
        when(tags.map(anyString())).thenReturn(new TagResultMapper.Mapped("OTHER",Map.of(),List.of(),List.of()));
        jdbc.update("UPDATE content_understanding SET input_json=? WHERE content_id=1",row.getInputJson().replace(",",", "));
        content.accept(event); content.accept(event);
        assertEquals(ContentReviewState.MANUAL_PENDING, understanding.selectById(1L).getState());
        assertTrue(understanding.selectById(1L).getReceived());
        assertEquals("",understanding.selectById(1L).getSummary());
        assertEquals("cover", jdbc.queryForObject("SELECT cover_object_key FROM content WHERE id=1", String.class));
        assertEquals(2L, jdbc.queryForObject("SELECT index_version FROM content WHERE id=1", Long.class));
        verify(artifacts,times(1)).read("summary",ContentArtifacts.Summary.class);
        verify(tasks,times(2)).received(100L);
        verifyNoInteractions(mq);
        assertEquals(0,count("content_publication_event"));
        assertEquals(1, count("pipeline_event_outbox"));
        assertTrue(jdbc.queryForObject("SELECT payload FROM pipeline_event_outbox", String.class).contains("ARTIFACTS_STORED"));
    }
    @Test void nativeArtifactsUseFrozenCoverAndReachSameManualPublicationOutboxWithoutVideoRow() {
        policy.setAutoReview(false); state(ContentReviewState.WAITING_PIPELINE);
        jdbc.update("DELETE FROM video"); jdbc.update("UPDATE content SET type='dynamic'");
        var dynamic = new DynamicMedia("vertical",1080,1920,"cut",0,List.of(
                new DynamicMedia.Image("first",0,3000,0.5,0.5),new DynamicMedia.Image("second",3000,3000,0.5,0.5)),null,6000);
        var input = new Input(100L,1L,9L,"图集","简介","","second",new ContentMedia("dynamic",null,dynamic),System.currentTimeMillis());
        jdbc.update("UPDATE content_understanding SET input_json=? WHERE content_id=1",JsonUtil.toJson(input));
        var results = new HashMap<>(Map.of("COVER","{\"coverObjectKey\":\"first\"}","ASR","{\"vttKey\":\"vtt\",\"segmentsKey\":\"asr\"}",
                "OCR","{\"artifactKey\":\"ocr\"}","SUMMARY","{\"artifactKey\":\"summary\"}","TAG","{\"artifactKey\":\"tag\"}"));
        when(artifacts.read("summary",ContentArtifacts.Summary.class)).thenReturn(new ContentArtifacts.Summary("图集摘要",List.of()));
        when(artifacts.read("tag",com.google.gson.JsonObject.class)).thenReturn(StructuredJson.object("{\"category\":\"OTHER\",\"tags\":[]}"));
        when(tags.map(anyString())).thenReturn(new TagResultMapper.Mapped("OTHER",Map.of(),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class, () -> content.accept(new ContentUnderstoodMessage(new Manifest(100L,1L,PipelineStatus.COMPLETED,input,results,System.currentTimeMillis()))));
        assertFalse(understanding.selectById(1L).getReceived());
        results.put("COVER","{\"coverObjectKey\":\"second\"}");
        content.accept(new ContentUnderstoodMessage(new Manifest(100L,1L,PipelineStatus.COMPLETED,input,results,System.currentTimeMillis())));
        assertEquals(ContentReviewState.MANUAL_PENDING,understanding.selectById(1L).getState());
        assertTrue(decisions.manual(1L,true,"完整资料人工确认",7L));
        assertEquals("published",jdbc.queryForObject("SELECT status FROM content WHERE id=1",String.class));
        assertEquals("second",jdbc.queryForObject("SELECT cover_object_key FROM content WHERE id=1",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT delta FROM content_publication_event",Integer.class));
        assertEquals(1L,jdbc.queryForObject("SELECT content_id FROM content_publication_event",Long.class));
        assertEquals(0,count("video"));
    }
    @Test void disablingAutoReviewFencesAlreadyRunningPass() {
        state(ContentReviewState.AUTO_RUNNING); policy.setAutoReview(false);
        policy.setReviewVisionEnabled(false);
        decisions.acceptResult(new ReviewResult(100L,1L,88L,1,"{\"decision\":\"PASS\",\"model\":\"test\"}",null,false));
        assertEquals(ContentReviewState.MANUAL_PENDING,understanding.selectById(1L).getState());
        assertFalse(decisions.begin(100L,2).enabled());
        assertEquals(0,count("content_publication_event"));
    }

    @Test void disablingVisionStillAllowsTextPassWhenAutoReviewIsEnabled() {
        state(ContentReviewState.AUTO_RUNNING);
        policy.setReviewVisionEnabled(false);

        decisions.acceptResult(new ReviewResult(100L, 1L, 88L, 1, JsonUtil.toJson(verdict("PASS")), null, false));

        assertEquals(ContentReviewState.APPROVED, understanding.selectById(1L).getState());
        assertEquals("published", jdbc.queryForObject("SELECT status FROM content WHERE id=1", String.class));
        assertEquals(1, count("content_review_record"));
        assertEquals(1, count("content_publication_event"));
        assertFalse(decisions.begin(100L, 2).enabled());
    }

    @Test void textReviewCanStartWhileVisionIsDisabledAndReceivesBatchConfiguration() {
        state(ContentReviewState.AUTO_PENDING);
        policy.setReviewVisionEnabled(false);
        frameSettings.replace("interval-seconds: 5\nmax-frames: 10\nmin-success-percent: 80\ncover-max-candidates: 8\nocr-batch-size: 2\nreview-batch-size: 3");

        var spec = decisions.begin(100L, 1);

        assertTrue(spec.enabled());
        assertFalse(spec.visionEnabled());
        assertEquals(3, spec.frameBatchSize());
        assertEquals(ContentReviewState.AUTO_RUNNING, understanding.selectById(1L).getState());
    }
    @Test void submissionAckCommitsAfterOuterTransactionAndRollbackNeverSends() {
        observations.getRecording().setEnabled(true);
        var video=new cn.diaojiahong.myyoutube.content.domain.po.Content().setId(1L).setAuthorId(9L).setTitle("标题");
        var media = new ContentMedia("video",new VideoMedia("processed.mp4",1000),null);
        tx.executeWithoutResult(status -> { content.enroll(video,media); status.setRollbackOnly(); });
        assertEquals(0,count("content_understanding")); verifyNoInteractions(mq);
        assertEquals(0, count("pipeline_event_outbox"));
        tx.executeWithoutResult(status -> content.enroll(video,media));
        assertTrue(understanding.selectById(1L).getSubmissionAck());
        verify(mq,times(1)).syncSend(anyString(),any(Object.class));
        assertEquals(1, count("pipeline_event_outbox"));
        assertTrue(jdbc.queryForObject("SELECT payload FROM pipeline_event_outbox", String.class).contains("CONTENT_SUBMITTED"));
    }
    @Test void outboxFailureRollsBackPublicationReviewAndAuditTogether() {
        observations.getRecording().setEnabled(true); state(ContentReviewState.AUTO_RUNNING);
        jdbc.execute("DROP TABLE pipeline_event_outbox");
        assertThrows(RuntimeException.class, () -> decisions.machine(1L, 1, verdict("PASS")));
        assertEquals("review", jdbc.queryForObject("SELECT status FROM content WHERE id=1", String.class));
        assertEquals(ContentReviewState.AUTO_RUNNING, understanding.selectById(1L).getState());
        assertEquals(0, count("content_review_record")); assertEquals(0, count("content_publication_event"));
        assertEquals(1L, jdbc.queryForObject("SELECT index_version FROM content WHERE id=1", Long.class));
        assertEquals(0L, understanding.selectById(1L).getEventSequence());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"video,PASS", "dynamic,PASS", "video,REJECT", "dynamic,REJECT", "video,BAN", "dynamic,BAN"})
    void manualReviewKeepsInternalRemarksButNotificationUsesFixedFeedback(String type, String decision) {
        String internal = "模型识别失败，人工复核记录和内部证据";
        jdbc.update("UPDATE content SET type=? WHERE id=1", type);
        if ("BAN".equals(decision)) {
            state(ContentReviewState.APPROVED);
            jdbc.update("UPDATE content SET status='published' WHERE id=1");
            assertTrue(decisions.banPublished(1L, internal, 7L));
        } else {
            state(ContentReviewState.MANUAL_PENDING);
            assertTrue(decisions.manual(1L, "PASS".equals(decision), internal, 7L));
        }
        var notifications = mock(cn.diaojiahong.myyoutube.notification.service.IUserNotificationService.class);
        when(notifications.save(any())).thenReturn(true);
        var events = new org.mybatis.spring.SqlSessionTemplate(contextFactory()).getMapper(ContentPublicationEventMapper.class);
        var effects = new PublicationEffectsService(events, mock(cn.diaojiahong.myyoutube.user.api.UserPort.class),
                mock(cn.diaojiahong.myyoutube.search.api.ContentDocPort.class), mock(cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher.class),
                notifications, mock(cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher.class), tx, mock(org.redisson.api.RedissonClient.class), mq);
        Long eventId = jdbc.queryForObject("SELECT id FROM content_publication_event", Long.class);
        effects.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(eventId, "NOTICE"));
        verify(notifications).save(argThat(n -> ("PASS".equals(decision)
                ? "您的内容已通过审核。" : "您的内容未通过审核，已限制展示。").equals(n.getContent())));
        assertEquals(internal, jdbc.queryForObject("SELECT reason FROM content_review_record", String.class));
        assertEquals(internal, jdbc.queryForObject("SELECT reason FROM content_publication_event", String.class));
        assertEquals(2L, jdbc.queryForObject("SELECT index_version FROM content WHERE id=1", Long.class));
    }

    @Test void publicationBranchesProgressDespiteCountFailureAndNoticeIsExactlyOnce() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_notice(id BIGINT AUTO_INCREMENT PRIMARY KEY)");
        jdbc.execute("TRUNCATE TABLE test_notice");
        jdbc.update("INSERT INTO content_publication_event(id,content_id,author_id,status,delta,reason) VALUES(50,1,9,'published',1,'通过')");
        var users=mock(cn.diaojiahong.myyoutube.user.api.UserPort.class);
        var search=mock(cn.diaojiahong.myyoutube.search.api.ContentDocPort.class);
        var es=mock(cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher.class);
        var notifications=mock(cn.diaojiahong.myyoutube.notification.service.IUserNotificationService.class);
        var push=mock(cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher.class);
        var redis=mock(org.redisson.api.RedissonClient.class); var lock=mock(org.redisson.api.RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock()).thenReturn(true);
        when(notifications.save(any())).thenAnswer(call -> { jdbc.update("INSERT INTO test_notice(id) VALUES(NULL)"); return true; });
        doThrow(new IllegalStateException("SSE down")).when(push).pushSseSignal(anyLong(),anyString());
        doThrow(new IllegalStateException("user down")).when(users).applyContentCountEvent(any());
        var service=new PublicationEffectsService(new org.mybatis.spring.SqlSessionTemplate(
                contextFactory()).getMapper(ContentPublicationEventMapper.class),users,search,es,notifications,push,tx,redis,mq);
        assertThrows(IllegalStateException.class,()->service.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(50L,"COUNT")));
        doThrow(new IllegalStateException("vector task delivery failed")).doNothing().when(search).rebuildContent(1L);
        assertThrows(IllegalStateException.class, () -> service.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(50L,"SEARCH")));
        assertEquals(0, jdbc.queryForObject("SELECT search_done FROM content_publication_event WHERE id=50", Integer.class));
        service.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(50L,"SEARCH"));
        service.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(50L,"NOTICE"));
        service.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(50L,"NOTICE"));
        assertEquals(1,count("test_notice"));
        assertEquals(0,jdbc.queryForObject("SELECT completed FROM content_publication_event WHERE id=50",Integer.class));
        doNothing().when(users).applyContentCountEvent(any());
        service.apply(new cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage(50L,"COUNT"));
        assertEquals(1,jdbc.queryForObject("SELECT completed FROM content_publication_event WHERE id=50",Integer.class));
        var order=inOrder(users,es); order.verify(users,times(2)).applyContentCountEvent(any()); order.verify(es).syncUserDoc(9L);
        verify(search,times(2)).rebuildContent(1L); verify(push,times(1)).pushSseSignal(anyLong(),anyString());
    }
    @Autowired SqlSessionFactory factory;
    SqlSessionFactory contextFactory() { return factory; }
}
