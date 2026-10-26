package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import java.time.Instant;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.mq.message.DraftPublicationMessage;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import cn.diaojiahong.myyoutube.video.config.PublicationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.controller.ContentDraftController;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.strategy.*;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.api.enums.*;
import cn.diaojiahong.myyoutube.video.chain.dynamic.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import cn.diaojiahong.myyoutube.video.domain.dto.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.service.*;
import cn.diaojiahong.myyoutube.video.service.impl.*;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(ContentDraftIntegrationTest.Config.class)
class ContentDraftIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = {MediaDraftMapper.class, ContentMapper.class})
    static class Config {
        @Bean DataSource dataSource() { return ContentTestDatabase.create("content-drafts"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds);
            var pagination = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.H2));
            bean.setPlugins(pagination); return bean.getObject();
        }
        @Bean TransactionTemplate transaction(DataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }
        @Bean cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations() { return new cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties(); }
        @Bean cn.diaojiahong.myyoutube.common.observability.PipelineEvents events(DataSource ds, TransactionTemplate tx, cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties props) {
            return new cn.diaojiahong.myyoutube.common.observability.PipelineEvents(props,
                    new cn.diaojiahong.myyoutube.common.observability.PipelineEventRecorder(new JdbcTemplate(ds), props, (DataSourceTransactionManager)tx.getTransactionManager()), "video", java.time.Clock.systemUTC());
        }
        @Bean cn.diaojiahong.myyoutube.video.service.DraftObservation observation(MediaDraftMapper rows, cn.diaojiahong.myyoutube.common.observability.PipelineEvents events, TransactionTemplate tx) {
            return new cn.diaojiahong.myyoutube.video.service.DraftObservation(rows, events, tx);
        }
        @Bean S3StorageUtil storage() { return mock(S3StorageUtil.class); }
        @Bean IAudioService audio() { return mock(IAudioService.class); }
        @Bean SimpleMeterRegistry publicationMetrics() { return new SimpleMeterRegistry(); }
        @Bean PublicationProperties publicationProperties() { return new PublicationProperties(); }
        @Bean DraftPublicationNotifications publicationNotifications(RocketMQTemplate mq, SimpleMeterRegistry metrics) {
            return new DraftPublicationNotifications(new AfterCommitNotifications(mq, metrics));
        }
        @Bean RocketMQTemplate mq() { return mock(RocketMQTemplate.class); }
        @Bean MediaTaskPort tasks() { return mock(MediaTaskPort.class); }
        @Bean ContentUnderstandingService understanding() { return mock(ContentUnderstandingService.class); }
        @Bean DynamicMediaPreparationService preparation(S3StorageUtil storage, IAudioService audio) {
            return new DynamicMediaPreparationService(new SyncChainStepRegistry(List.of(
                    new ItemsCountStep(), new CanvasStyleStep(), new AudioReferenceStep(audio), new ItemsPrepareStep())));
        }
        @Bean ContentSubmissionRules rules() { return new ContentSubmissionRules("content-moderation/blocked.txt"); }
        @Bean ContentDraftService drafts(MediaDraftMapper rows, DynamicMediaPreparationService preparation, cn.diaojiahong.myyoutube.video.service.DraftObservation observation) {
            return new ContentDraftService(rows, preparation, observation, rules());
        }
        @Bean cn.diaojiahong.myyoutube.video.service.DraftPreparationService coordinator(MediaDraftMapper rows, MediaTaskPort tasks, cn.diaojiahong.myyoutube.video.service.DraftObservation observation, DraftPublicationNotifications notifications) {
            return new cn.diaojiahong.myyoutube.video.service.DraftPreparationService(rows, tasks, observation, notifications);
        }
        @Bean DraftPublicationService publications(MediaDraftMapper rows, DraftObservation observation,
                ContentSubmissionService publish, ContentDraftService drafts, DraftPreparationService preparation, PublicationProperties properties,
                DraftPublicationNotifications notifications, SimpleMeterRegistry metrics) {
            return new DraftPublicationService(rows, observation, publish, drafts, preparation, rules(), properties, notifications, metrics);
        }
        @Bean MediaDraftService mediaDrafts(MediaDraftMapper rows, cn.diaojiahong.myyoutube.video.service.DraftObservation observation) { return new MediaDraftService(rows, observation); }
        @Bean ContentSubmissionService publish(MediaDraftService drafts, MediaTaskPort tasks, VideoMapper videos,
                                            DynamicMapper dynamics, DynamicMediaPreparationService preparation,
                                            ContentMapper contents, ContentUnderstandingService understanding, TransactionTemplate tx, IAudioService audio) {
            var strategies = new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos), new DynamicContentStrategy(dynamics, audio)));
            return new ContentSubmissionService(drafts, contents, strategies, understanding, tx, rules());
        }
    }

    @Autowired DataSource dataSource;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    @Autowired cn.diaojiahong.myyoutube.video.service.DraftObservation observation;
    @Autowired ContentDraftService drafts;
    @Autowired DynamicMediaPreparationService mediaPreparation;
    @Autowired cn.diaojiahong.myyoutube.video.service.DraftPreparationService coordinator;
    @Autowired ContentMapper contentRows;
    @Autowired TransactionTemplate transaction;
    @Autowired MediaDraftService mediaDrafts;
    @Autowired MediaDraftMapper rows;
    @Autowired ContentSubmissionService publish;
    @Autowired DraftPublicationService publications;
    @Autowired MediaTaskPort tasks;
    @Autowired RocketMQTemplate mq;
    @Autowired PublicationProperties properties;
    @Autowired DraftPublicationNotifications notifications;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired ContentUnderstandingService understanding;
    @Autowired S3StorageUtil storage;
    @Autowired IAudioService audio;
    JdbcTemplate jdbc;
    final String first = "uploads/picture/9/20261011/a.png", second = "uploads/picture/9/20261011/b.png";

    @BeforeEach void schema() throws Exception {
        reset(tasks, understanding, storage, audio, mq);
        metrics.clear();
        properties.setBatchSize(100); properties.setLeaseSeconds(30); properties.setWaitingSeconds(15);
        properties.setDeadlineMinutes(30); properties.setMaxAttempts(3); properties.setRetryBackoffSeconds(List.of(5, 30, 120));
        observations.getRecording().setEnabled(false);
        UserContext.setUser("9");
        jdbc = new JdbcTemplate(dataSource);
        ContentTestDatabase.outbox(jdbc);
        ContentTestDatabase.drop(jdbc,"media_draft","video","dynamic","content");
        jdbc.execute("""
                CREATE TABLE media_draft(event_sequence BIGINT NOT NULL DEFAULT 0,id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,type VARCHAR(16) NOT NULL,
                  title VARCHAR(100),description VARCHAR(512),visibility VARCHAR(16) DEFAULT 'public',allow_comment INT DEFAULT 1,
                  media_version BIGINT DEFAULT 0,preparation_id BIGINT,preparation_attempt INT DEFAULT 0,preparation_retry BOOLEAN DEFAULT FALSE,
                  submission_state VARCHAR(16) NOT NULL DEFAULT 'none',submission_revision INT,submission_attempts INT NOT NULL DEFAULT 0,
                  submission_after TIMESTAMP,submission_deadline TIMESTAMP,submission_error VARCHAR(500),
                  expires_at TIMESTAMP,preparation_after TIMESTAMP,prepared_json TEXT,prepared_cover_object_key VARCHAR(512),
                  revision INT DEFAULT 0,cover_object_key VARCHAR(512),media_json TEXT,object_key VARCHAR(512) UNIQUE,original_filename VARCHAR(256),status VARCHAR(16),
                  duration_ms BIGINT,error_message VARCHAR(500),create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE video(id BIGINT PRIMARY KEY,object_key VARCHAR(512),duration_ms BIGINT)");
        jdbc.execute("CREATE TABLE dynamic(id BIGINT PRIMARY KEY,audio_id BIGINT,media_json TEXT)");
        jdbc.execute("CREATE TABLE content(id BIGINT PRIMARY KEY,type VARCHAR(16),author_id BIGINT,title VARCHAR(100),description TEXT,cover_object_key VARCHAR(512),category_code VARCHAR(32),tags TEXT,allow_comment INT,region VARCHAR(32),is_delete INT DEFAULT 0,status VARCHAR(16),visibility VARCHAR(16),index_version BIGINT NOT NULL DEFAULT 1,play_count BIGINT DEFAULT 0,like_count BIGINT DEFAULT 0,collect_count BIGINT DEFAULT 0,comment_count BIGINT DEFAULT 0,share_count BIGINT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
        when(understanding.enroll(any(), any())).thenReturn(100L);
        when(understanding.get(anyLong())).thenAnswer(call -> new ContentUnderstanding().setContentId(call.getArgument(0)).setPipelineId(100L));
        when(storage.readHeadBytes(anyString(), anyInt())).thenReturn("\u0089PNG\r\n\u001a\n12345678".getBytes(StandardCharsets.ISO_8859_1));
    }
    @AfterEach void clear() { UserContext.removeUser(); }

    DynamicDraftDTO dynamic() {
        var dto = new DynamicDraftDTO(); dto.setTitle(" 标题 "); dto.setDescription(" 简介 ");
        var a = new DynamicDraftDTO.DynamicItemDTO(); a.setImageObjectKey(first);
        var b = new DynamicDraftDTO.DynamicItemDTO(); b.setImageObjectKey(second);
        dto.setItems(List.of(a, b)); return dto;
    }

    @Test void nativeDraftIsProcessingWithoutMediaIoAndSupportsSelectedCover() {
        var dto = dynamic(); dto.setCoverImageObjectKey(second); dto.setAllowComment(0);
        var result = drafts.saveDynamic(null, 9L, dto);
        assertEquals("processing", result.status()); assertEquals("dynamic", result.type());
        assertEquals("标题", result.title()); assertEquals("简介", result.description());
        assertEquals(second, result.coverObjectKey());
        assertEquals(List.of(first, second), result.dynamic().items().stream().map(i -> i.imageObjectKey()).toList());
        assertEquals(5200, result.dynamic().exportDurationMs()); assertNull(result.dynamic().audio());
        assertEquals("public", result.visibility()); assertEquals(0, result.allowComment());
        assertEquals(result, drafts.get(result.draftId(), 9L));
        assertNull(result.objectKey());
        verifyNoInteractions(tasks, understanding, audio, storage);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }

    @Test void longMusicPreservesOriginalDurationAndDoesNotStretchImages() {
        when(audio.getReferenceable(20L, 9L)).thenReturn(new Audio().setId(20L).setObjectKey("uploads/audio/7/20261011/music.mp3").setDurationMs(120000));
        var dto = dynamic(); dto.setAudioId(20L);
        var media = drafts.saveDynamic(null, 9L, dto).dynamic();
        assertEquals(120000, media.audio().durationMs()); assertEquals(5200, media.exportDurationMs());
        assertEquals(3000, media.items().get(0).durationMs());
        verify(audio).getReferenceable(20L, 9L);
    }

    @Test void missingOrUnauthorizedAudioFailsInsteadOfPretendingNoAudio() {
        var dto = dynamic(); dto.setAudioId(20L);
        when(audio.getReferenceable(20L, 9L)).thenThrow(new BizException(ErrorCode.FORBIDDEN, "无权使用该音频"));
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto));
        doReturn(new Audio().setId(20L).setObjectKey("uploads/audio/7/20261011/music.mp3").setDurationMs(2000)).when(audio).getReferenceable(20L, 9L);
        var draft = drafts.saveDynamic(null, 9L, dto);
        assertEquals("processing", draft.status());
        verifyNoInteractions(storage);
    }

    @Test void invalidInputsCannotCreateReadyDrafts() {
        var dto = dynamic(); dto.setTitle(" ");
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto)); dto.setTitle("标题");
        dto.setCoverImageObjectKey("uploads/picture/9/20261011/other.png");
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto)); dto.setCoverImageObjectKey(null);
        dto.getItems().get(0).setDurationMs(800L);
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto)); dto.getItems().get(0).setDurationMs(null);
        dto.getItems().get(0).setImageObjectKey("uploads/picture/7/9/a.png");
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto)); dto.getItems().get(0).setImageObjectKey(first);
        dto.getItems().get(0).setFocusX(Double.NaN);
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto)); dto.getItems().get(0).setFocusX(null);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft", Integer.class));
        assertEquals("processing", drafts.saveDynamic(null, 9L, dto).status());
        verifyNoInteractions(storage);
    }

    @Test void ownershipAndStateProtectEditsAndDescriptionCanBeCleared() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        assertThrows(BizException.class, () -> drafts.get(created.draftId(), 10L));
        assertThrows(BizException.class, () -> drafts.saveDynamic(created.draftId(), 10L, dynamic()));
        var dto = new ContentMetadataDTO(); dto.setTitle("更新");
        assertNull(drafts.saveMetadata(created.draftId(), 9L, dto).description());
        jdbc.update("UPDATE media_draft SET status='submitted' WHERE id=?", created.draftId());
        assertThrows(BizException.class, () -> drafts.saveMetadata(created.draftId(), 9L, dto));
        assertThrows(BizException.class, () -> drafts.saveDynamic(created.draftId(), 9L, dynamic()));
        assertEquals("更新", drafts.get(created.draftId(), 9L).title());
    }

    @Test void blockedDynamicCreationDoesNotPrepareOrPersistAnything() {
        var dto = dynamic(); dto.setTitle("裸聊加微信");
        assertEquals("标题包含不允许的内容，请修改", assertThrows(BizException.class,
                () -> drafts.saveDynamic(null, 9L, dto)).getMessage());
        dto.setTitle("正常标题"); dto.setDescription("网赌充值送彩金");
        assertEquals("简介包含不允许的内容，请修改", assertThrows(BizException.class,
                () -> drafts.saveDynamic(null, 9L, dto)).getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        verifyNoInteractions(audio, storage, tasks, understanding);
    }

    @Test void blockedDynamicAndMetadataEditsPreserveTheSavedDraft() {
        observations.getRecording().setEnabled(true);
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var original = rows.selectById(created.draftId());
        int eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class);
        var dto = dynamic(); dto.setTitle("裸聊加微信");
        assertThrows(BizException.class, () -> drafts.saveDynamic(created.draftId(), 9L, dto));
        dto.setTitle("更新标题"); dto.setDescription("网赌充值送彩金");
        assertThrows(BizException.class, () -> drafts.saveDynamic(created.draftId(), 9L, dto));
        var metadata = new ContentMetadataDTO(); metadata.setTitle("更新标题"); metadata.setDescription("代考替考包过");
        assertThrows(BizException.class, () -> drafts.saveMetadata(created.draftId(), 9L, metadata));
        metadata.setTitle("裸聊加微信"); metadata.setDescription(null);
        assertThrows(BizException.class, () -> drafts.saveMetadata(created.draftId(), 9L, metadata));
        var current = rows.selectById(created.draftId());
        assertEquals(original.getTitle(), current.getTitle());
        assertEquals(original.getDescription(), current.getDescription());
        assertEquals(original.getRevision(), current.getRevision());
        assertEquals(original.getPreparationId(), current.getPreparationId());
        assertEquals(original.getExpiresAt(), current.getExpiresAt());
        assertEquals(original.getEventSequence(), current.getEventSequence());
        assertEquals(eventCount, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
    }

    @Test void submissionRechecksPersistedVideoAndDynamicTextWithoutFreezingOrEnrollment() {
        for (boolean dynamic : new boolean[]{false, true}) {
            Long id;
            if (dynamic) {
                id = drafts.saveDynamic(null, 9L, dynamic()).draftId(); ready(id);
            } else id = readyVideo();
            var original = rows.selectById(id);
            clearInvocations(understanding, tasks);
            jdbc.update("UPDATE media_draft SET title='裸聊加微信' WHERE id=?", id);
            assertEquals("标题包含不允许的内容，请修改", assertThrows(BizException.class, () -> submitClaimed(publish, id, 9L)).getMessage());
            jdbc.update("UPDATE media_draft SET title='正常标题',description='网赌充值送彩金' WHERE id=?", id);
            assertEquals("简介包含不允许的内容，请修改", assertThrows(BizException.class, () -> submitClaimed(publish, id, 9L)).getMessage());
            assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
            assertEquals(original.getRevision(), rows.selectById(id).getRevision());
            assertEquals(original.getEventSequence(), rows.selectById(id).getEventSequence());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM video", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dynamic", Integer.class));
            verifyNoInteractions(understanding, tasks);
        }
    }

    @Test void contextualTextKeepsItsOriginalCharactersAndStillEntersReview() {
        var dto = dynamic(); dto.setTitle("奉劝大家不要赌博 ＡＢＣ\u200b");
        dto.setDescription("警方打击出售个人信息的行为\n保留  原始格式与全角２０２６");
        Long id = drafts.saveDynamic(null, 9L, dto).draftId(); ready(id);
        var before = Instant.now().minusSeconds(1);
        submitClaimed(publish, id, 9L);
        var created = BusinessTime.toInstant(contentRows.selectById(id).getCreateTime());
        assertFalse(created.isBefore(before)); assertFalse(created.isAfter(Instant.now().plusSeconds(1)));
        assertEquals(dto.getTitle(), rows.selectById(id).getTitle());
        assertEquals(dto.getDescription(), rows.selectById(id).getDescription());
        assertEquals(dto.getTitle(), contentRows.selectById(id).getTitle());
        assertEquals(dto.getDescription(), contentRows.selectById(id).getDescription());
        assertEquals(ContentStatusEnum.REVIEW, contentRows.selectById(id).getStatus());
        verify(understanding).enroll(argThat(c -> c.getTitle().equals(dto.getTitle()) && c.getDescription().equals(dto.getDescription())), any());
    }

    @Test void httpRoutesRejectStandaloneGamblingAtCreationMetadataEditAndSubmission() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ContentDraftController(drafts, publications, coordinator, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var input = dynamic(); input.setTitle("赌博");
        mvc.perform(post("/contents/drafts/dynamic").contentType("application/json")
                        .content(cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(input)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("标题包含不允许的内容，请修改"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft", Integer.class));
        for (boolean dynamic : new boolean[]{false, true}) {
            Long id;
            if (dynamic) {
                id = drafts.saveDynamic(null, 9L, dynamic()).draftId(); ready(id);
                mvc.perform(put("/contents/drafts/{id}/dynamic", id).contentType("application/json")
                                .content(cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(input)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("标题包含不允许的内容，请修改"));
            } else id = readyVideo();
            var original = rows.selectById(id);
            clearInvocations(tasks, understanding);
            for (String field : new String[]{"title", "description"}) {
                var metadata = new ContentMetadataDTO(); metadata.setTitle(field.equals("title") ? "赌博" : "正常标题");
                metadata.setDescription(field.equals("description") ? "赌博" : null);
                String message = (field.equals("title") ? "标题" : "简介") + "包含不允许的内容，请修改";
                mvc.perform(put("/contents/drafts/{id}/metadata", id).contentType("application/json")
                                .content(cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(metadata)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
                assertEquals(original.getRevision(), rows.selectById(id).getRevision());
                assertEquals(original.getTitle(), rows.selectById(id).getTitle());
                assertEquals(original.getDescription(), rows.selectById(id).getDescription());
            }
            jdbc.update("UPDATE media_draft SET title='赌博',description=NULL WHERE id=?", id);
            mvc.perform(post("/contents/drafts/{id}/submit", id).contentType("application/json")
                    .content("{\"revision\":" + original.getRevision() + "}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("标题包含不允许的内容，请修改"));
            jdbc.update("UPDATE media_draft SET title='正常标题',description='赌博' WHERE id=?", id);
            mvc.perform(post("/contents/drafts/{id}/submit", id).contentType("application/json")
                    .content("{\"revision\":" + original.getRevision() + "}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("简介包含不允许的内容，请修改"));
            assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
            assertEquals(original.getRevision(), rows.selectById(id).getRevision());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
            verifyNoInteractions(understanding, tasks);
            mvc.perform(put("/contents/drafts/{id}/metadata", id).contentType("application/json")
                            .content("{\"title\":\"奉劝大家不要赌博\",\"description\":\"警方打击赌博平台\",\"allowComment\":1}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("奉劝大家不要赌博"));
        }
    }

    void reconcileDue() {
        jdbc.update("UPDATE media_draft SET preparation_after=? WHERE status='processing'", java.time.LocalDateTime.now().minusSeconds(1));
        coordinator.reconcile();
    }

    void ready(Long id) {
        var row = rows.selectById(id);
        var request = PreparedDraftTestSupport.request(row);
        when(tasks.preparation(row.getPreparationId())).thenReturn(new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(
                row.getPreparationId(), MediaTaskStatus.SUCCESS, 1, 0, null, PreparedDraftTestSupport.manifest(request), false));
        jdbc.update("UPDATE media_draft SET preparation_after=CURRENT_TIMESTAMP WHERE id=?", id);
        reconcileDue();
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
    }
    Long readyVideo() {
        Long id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/original.mp4", "original.mp4");
        assertTrue(coordinator.uploaded(id)); ready(id); return id;
    }
    @Test void draftEventsUseLockedEntitySequenceAndIgnoreRepeatedUploadAndReconcile() {
        observations.getRecording().setEnabled(true);
        Long id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/observed.mp4", "observed.mp4");
        assertTrue(coordinator.uploaded(id)); assertFalse(coordinator.uploaded(id)); ready(id);
        reconcileDue();
        assertEquals(List.of(1L, 2L, 3L), jdbc.queryForList("SELECT event_sequence FROM pipeline_event_outbox WHERE aggregate_id=? ORDER BY event_sequence", Long.class, id.toString()));
        assertEquals(3L, rows.selectById(id).getEventSequence());
        var payload = StructuredJson.object(jdbc.queryForObject("SELECT payload FROM pipeline_event_outbox WHERE aggregate_id=? AND event_sequence=3", String.class, id.toString()));
        assertEquals(id.toString(), payload.get("draftId").getAsString());
        assertEquals(rows.selectById(id).getPreparationId().toString(), payload.get("preparationId").getAsString());
    }
    @Test void failedSubmissionRollsBackDraftSequenceAndEvent() {
        observations.getRecording().setEnabled(true); Long id = readyVideo();
        jdbc.update("UPDATE media_draft SET title='标题' WHERE id=?", id);
        long sequence = rows.selectById(id).getEventSequence();
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class);
        doThrow(new IllegalStateException("registration failed")).when(understanding).enroll(any(), any());
        assertThrows(IllegalStateException.class, () -> submitClaimed(publish, id, 9L));
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(sequence, rows.selectById(id).getEventSequence());
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
    }

    @Test void concurrentSubmissionCreatesOneContentAndOneEnrollment() {
        Long id = readyVideo();
        var dto = new ContentMetadataDTO(); dto.setTitle("视频");
        drafts.saveMetadata(id, 9L, dto);
        var a = CompletableFuture.supplyAsync(() -> submitClaimed(publish, id, 9L));
        var b = CompletableFuture.supplyAsync(() -> submitClaimed(publish, id, 9L));
        assertEquals(a.join(), b.join());
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
        assertEquals(1501L, rows.selectById(id).getDurationMs());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM video", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        assertEquals("public", jdbc.queryForObject("SELECT visibility FROM content", String.class));
        assertEquals("public", rows.selectById(id).getVisibility());
        assertEquals("review", jdbc.queryForObject("SELECT status FROM content", String.class));
        verify(understanding, times(1)).enroll(any(), argThat(m -> m.video().durationMs() == 1501));
        assertEquals(1501L, jdbc.queryForObject("SELECT duration_ms FROM video", Long.class));
    }

    @Test void failedEnrollmentRollsBackFreezeAndContentCreation() {
        Long id = readyVideo();
        var dto = new ContentMetadataDTO(); dto.setTitle("视频");
        drafts.saveMetadata(id, 9L, dto);
        doThrow(new IllegalStateException("登记失败")).when(understanding).enroll(any(), any());
        assertThrows(IllegalStateException.class, () -> submitClaimed(publish, id, 9L));
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM video", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }

    @Test void nativeSubmissionFreezesMediaAndHasNoTranscodeDependency() {
        var dto = dynamic(); dto.setCoverImageObjectKey(second); dto.setAllowComment(0);
        var draft = drafts.saveDynamic(null, 9L, dto);
        ready(draft.draftId()); clearInvocations(tasks);
        var firstReceipt = submitClaimed(publish, draft.draftId(), 9L);
        assertEquals(firstReceipt, submitClaimed(publish, draft.draftId(), 9L));
        assertEquals("submitted", firstReceipt.draftStatus());
        assertEquals("review", jdbc.queryForObject("SELECT status FROM content", String.class));
        assertEquals(rows.selectById(draft.draftId()).getPreparedCoverObjectKey(), jdbc.queryForObject("SELECT cover_object_key FROM content", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT allow_comment FROM content", Integer.class));
        assertEquals(cn.diaojiahong.myyoutube.common.utils.JsonUtil.fromJson(rows.selectById(draft.draftId()).getPreparedJson(), cn.diaojiahong.myyoutube.common.media.ContentMedia.class).dynamic(),
                cn.diaojiahong.myyoutube.common.utils.JsonUtil.fromJson(jdbc.queryForObject("SELECT media_json FROM dynamic", String.class), cn.diaojiahong.myyoutube.common.media.DynamicMedia.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM video", Integer.class));
        verify(understanding, times(1)).enroll(any(), argThat(m -> m.dynamic() != null));
        verifyNoInteractions(tasks);
    }
    @Test void formalSubmissionRechecksAudioInTheFreezeTransactionAndPersistsItsIdentity() {
        var dto = dynamic(); dto.setAudioId(20L);
        when(audio.getReferenceable(20L, 9L)).thenReturn(new Audio().setId(20L)
                .setObjectKey("media-prepared/7/20/audio-source").setDurationMs(120000));
        var draft = drafts.saveDynamic(null, 9L, dto);
        ready(draft.draftId());
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
            return new Audio().setId(20L);
        }).when(audio).lockReferenceable(20L, 9L);
        var receipt = submitClaimed(publish, draft.draftId(), 9L);
        assertEquals(20L, jdbc.queryForObject("SELECT audio_id FROM dynamic WHERE id=?", Long.class, receipt.contentId()));
        assertEquals(receipt, submitClaimed(publish, draft.draftId(), 9L));
        verify(audio, times(1)).lockReferenceable(20L, 9L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"FORBIDDEN", "NOT_FOUND", "INVALID_ARGUMENT"})
    void invalidAudioAtFormalSubmissionFailsPublicationWithoutFreezingTheDraft(ErrorCode code) {
        var dto = dynamic(); dto.setAudioId(20L);
        when(audio.getReferenceable(20L, 9L)).thenReturn(new Audio().setId(20L)
                .setObjectKey("uploads/audio/7/day/music.mp3").setDurationMs(120000));
        var draft = drafts.saveDynamic(null, 9L, dto);
        ready(draft.draftId());
        doThrow(new BizException(code, "所选音频不可用，请重新选择")).when(audio).lockReferenceable(20L, 9L);
        publications.request(draft.draftId(), 9L, rows.selectById(draft.draftId()).getRevision());
        publications.scan();
        var current = rows.selectById(draft.draftId());
        assertEquals(DraftSubmissionState.FAILED, current.getSubmissionState());
        assertEquals(MediaDraftStatusEnum.READY, current.getStatus());
        assertEquals(1, current.getSubmissionAttempts());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dynamic", Integer.class));
        verify(understanding, never()).enroll(any(), any());
    }

    @Test void editDuringMaterialValidationCannotSubmitOldSnapshot() {
        Long id = readyVideo();
        var metadata = new ContentMetadataDTO(); metadata.setTitle("初始"); drafts.saveMetadata(id, 9L, metadata);
        var strategy = mock(cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategy.class);
        when(strategy.type()).thenReturn(cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum.VIDEO);
        when(strategy.prepare(any())).thenAnswer(call -> {
            var media = cn.diaojiahong.myyoutube.content.strategy.PreparedDrafts.media(call.getArgument(0));
            publications.cancel(id, 9L, rows.selectById(id).getRevision());
            metadata.setTitle("更新"); drafts.saveMetadata(id, 9L, metadata); return media;
        });
        var racing = new ContentSubmissionService(mediaDrafts, contentRows,
                new ContentTypeStrategyRegistry(List.of(strategy)), understanding, transaction, new ContentSubmissionRules("content-moderation/blocked.txt"));
        assertThrows(BizException.class, () -> submitClaimed(racing, id, 9L));
        assertEquals("更新", rows.selectById(id).getTitle());
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, never()).enroll(any(), any());
    }
    @Test void submittedDynamicRetainsAudioGrantAfterPermissionsChange() {
        var dto = dynamic(); dto.setAudioId(20L);
        when(audio.getReferenceable(20L, 9L)).thenReturn(new Audio().setId(20L).setObjectKey("uploads/audio/7/20261011/music.mp3").setDurationMs(2000));
        var draft = drafts.saveDynamic(null, 9L, dto);
        ready(draft.draftId());
        var receipt = submitClaimed(publish, draft.draftId(), 9L);
        clearInvocations(audio, storage, tasks);
        when(audio.lockReferenceable(20L, 9L)).thenThrow(new BizException(ErrorCode.FORBIDDEN, "已移除音频权限"));
        assertEquals(receipt, submitClaimed(publish, draft.draftId(), 9L));
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(draft.draftId()).getStatus());
        verifyNoInteractions(audio, storage, tasks);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }
    @Test void processingDraftCannotSubmitAndMetadataEditsDoNotInvalidatePreparation() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        assertThrows(BizException.class, () -> submitClaimed(publish, created.draftId(), 9L));
        publications.cancel(created.draftId(), 9L, rows.selectById(created.draftId()).getRevision());
        var metadata = new ContentMetadataDTO(); metadata.setTitle("新标题");
        var edited = drafts.saveMetadata(created.draftId(), 9L, metadata);
        assertEquals(created.preparationId(), edited.preparationId()); assertEquals(created.mediaVersion(), edited.mediaVersion());
        ready(created.draftId());
        var ready = drafts.get(created.draftId(), 9L);
        var unchanged = dynamic(); unchanged.setTitle("再改标题");
        var saved = drafts.saveDynamic(created.draftId(), 9L, unchanged);
        assertEquals("ready", saved.status()); assertEquals(ready.preparationId(), saved.preparationId());
        assertEquals(ready.mediaVersion(), saved.mediaVersion());
        assertNotNull(submitClaimed(publish, created.draftId(), 9L));
        verifyNoInteractions(storage);
    }

    @Test void oldPreparationResponseCannotReadyReplacementMedia() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var row = rows.selectById(created.draftId());
        var request = PreparedDraftTestSupport.request(row);
        when(tasks.preparation(created.preparationId())).thenAnswer(call -> {
            var replacement = dynamic(); replacement.getItems().get(1).setImageObjectKey("uploads/picture/9/20261011/replaced.png");
            drafts.saveDynamic(created.draftId(), 9L, replacement);
            return new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(created.preparationId(), MediaTaskStatus.SUCCESS, 1, 0, null,
                    PreparedDraftTestSupport.manifest(request), false);
        });
        reconcileDue();
        var current = rows.selectById(created.draftId());
        assertEquals(MediaDraftStatusEnum.PROCESSING, current.getStatus()); assertNull(current.getPreparedJson());
        assertEquals(1, current.getMediaVersion()); assertNotEquals(created.preparationId(), current.getPreparationId());
        assertThrows(BizException.class, () -> submitClaimed(publish, created.draftId(), 9L));
        ready(created.draftId()); assertNotNull(submitClaimed(publish, created.draftId(), 9L));
    }

    @Test void deadTaskRetryRejectsLateFailureAndKeepsSamePreparation() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var row = rows.selectById(created.draftId()); var request = PreparedDraftTestSupport.request(row);
        var dead = new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(created.preparationId(), MediaTaskStatus.DEAD, 4, 3, "下载失败", null, false);
        when(tasks.preparation(created.preparationId())).thenReturn(dead);
        reconcileDue(); assertEquals("failed", drafts.get(created.draftId(), 9L).status());
        assertTrue(coordinator.retry(created.draftId(), 9L)); assertTrue(coordinator.retry(created.draftId(), 9L));
        when(tasks.retryPreparation(created.preparationId())).thenReturn(true);
        reconcileDue(); assertEquals("processing", drafts.get(created.draftId(), 9L).status());
        assertEquals(5, rows.selectById(created.draftId()).getPreparationAttempt());
        when(tasks.preparation(created.preparationId())).thenReturn(new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(
                created.preparationId(), MediaTaskStatus.SUCCESS, 5, 0, null, PreparedDraftTestSupport.manifest(request), false));
        jdbc.update("UPDATE media_draft SET preparation_after=CURRENT_TIMESTAMP WHERE id=?", created.draftId());
        reconcileDue(); assertEquals("ready", drafts.get(created.draftId(), 9L).status());
        assertEquals(created.preparationId(), drafts.get(created.draftId(), 9L).preparationId());
        assertThrows(BizException.class, () -> coordinator.retry(created.draftId(), 10L));
    }

    @Test void failedRegistrationIsRecoveredFromPersistedIntent() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        doThrow(new IllegalStateException("MQ unavailable")).when(tasks).prepare(any());
        reconcileDue(); assertEquals("processing", drafts.get(created.draftId(), 9L).status());
        doNothing().when(tasks).prepare(any()); ready(created.draftId());
        verify(tasks, times(2)).prepare(argThat(r -> r.preparationId().equals(created.preparationId())));
    }

    @Test void videoRetryAfterServerConfigurationChangeCreatesNewVersionWithoutReupload() {
        var id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/original.mp4", "original.mp4");
        assertTrue(coordinator.uploaded(id)); retryChangedConfiguration(id);
    }

    @Test void dynamicRetryAfterServerConfigurationChangeCreatesNewVersionWithSameMedia() {
        retryChangedConfiguration(drafts.saveDynamic(null, 9L, dynamic()).draftId());
    }

    void retryChangedConfiguration(Long id) {
        var before = rows.selectById(id);
        when(tasks.preparation(before.getPreparationId())).thenReturn(new PreparationContracts.View(
                before.getPreparationId(), MediaTaskStatus.DEAD, 4, 3, PreparationContracts.PARAMETERS_CHANGED_MESSAGE, null, true));
        reconcileDue(); assertEquals("failed", drafts.get(id, 9L).status());
        assertTrue(coordinator.retry(id, 9L));
        var renewed = rows.selectById(id);
        assertEquals(before.getMediaVersion() + 1, renewed.getMediaVersion());
        assertNotEquals(before.getPreparationId(), renewed.getPreparationId());
        assertEquals(before.getRevision() + 1, renewed.getRevision());
        assertEquals(before.getObjectKey(), renewed.getObjectKey());
        assertEquals(before.getMediaJson(), renewed.getMediaJson());
        assertEquals(before.getCoverObjectKey(), renewed.getCoverObjectKey());
        assertEquals(0, renewed.getPreparationAttempt()); assertFalse(renewed.getPreparationRetry());
        assertNull(renewed.getErrorMessage()); assertNull(renewed.getPreparedJson());
        assertNull(renewed.getPreparedCoverObjectKey());
        assertTrue(coordinator.retry(id, 9L));
        assertEquals(renewed.getPreparationId(), rows.selectById(id).getPreparationId());
        assertEquals("RELEASED", reference(id, before.getPreparationId(), before.getMediaVersion()));
        ready(id);
        verify(tasks).prepare(argThat(r -> r.preparationId().equals(renewed.getPreparationId()) && r.mediaVersion() == 1));
        verify(tasks, never()).retryPreparation(anyLong()); verifyNoInteractions(storage);
    }

    @Test void retryCannotReplaceMediaEditedWhileCheckingConfiguration() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var dead = new PreparationContracts.View(created.preparationId(), MediaTaskStatus.DEAD, 4, 3, "旧配置", null, true);
        when(tasks.preparation(created.preparationId())).thenReturn(dead); reconcileDue();
        when(tasks.preparation(created.preparationId())).thenAnswer(call -> {
            var replacement = dynamic(); replacement.getItems().get(1).setImageObjectKey("uploads/picture/9/20261011/replaced.png");
            drafts.saveDynamic(created.draftId(), 9L, replacement); return dead;
        });
        assertFalse(coordinator.retry(created.draftId(), 9L));
        var current = rows.selectById(created.draftId());
        assertEquals(1, current.getMediaVersion());
        assertTrue(current.getMediaJson().contains("replaced.png"));
        assertEquals(MediaDraftStatusEnum.PROCESSING, current.getStatus());
        assertEquals(1, current.getRevision()); verify(tasks, never()).retryPreparation(anyLong());
    }

    @Test void unavailableConfigurationCheckLeavesFailedDraftRetryable() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        when(tasks.preparation(created.preparationId())).thenReturn(new PreparationContracts.View(
                created.preparationId(), MediaTaskStatus.DEAD, 4, 3, "下载失败", null, false));
        reconcileDue();
        when(tasks.preparation(created.preparationId())).thenReturn(null);
        assertEquals(ErrorCode.DEPENDENCY_FAILURE,
                assertThrows(DependencyException.class, () -> coordinator.retry(created.draftId(), 9L)).getErrorCode());
        var current = rows.selectById(created.draftId());
        assertEquals(MediaDraftStatusEnum.FAILED, current.getStatus());
        assertEquals(created.preparationId(), current.getPreparationId()); assertEquals(4, current.getPreparationAttempt());
        verify(tasks, never()).retryPreparation(anyLong());
    }

    @Test void incompleteOrMismatchedManifestCannotReadyDraft() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var request = PreparedDraftTestSupport.request(rows.selectById(created.draftId()));
        var valid = PreparedDraftTestSupport.manifest(request);
        var incomplete = new cn.diaojiahong.myyoutube.common.media.PreparationContracts.Manifest(valid.preparationId(), valid.draftId(),
                valid.mediaVersion(), valid.userId(), valid.parameterVersion(), valid.items().subList(0, 3), valid.durationMs(), valid.hasAudio(), valid.videoFrameCount(), valid.videoSampling());
        when(tasks.preparation(created.preparationId())).thenReturn(new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(
                created.preparationId(), MediaTaskStatus.SUCCESS, 1, 0, null, incomplete, false));
        reconcileDue(); assertEquals("processing", drafts.get(created.draftId(), 9L).status());
        assertNull(rows.selectById(created.draftId()).getPreparedJson());
        assertThrows(BizException.class, () -> submitClaimed(publish, created.draftId(), 9L));
        ready(created.draftId());
        jdbc.update("UPDATE media_draft SET media_version=media_version+1 WHERE id=?", created.draftId());
        assertNotNull(assertThrows(BizException.class, () -> submitClaimed(publish, created.draftId(), 9L)).getCause());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }
    @Test void legacyStatusAndTaskRoutesAreRemoved() throws Exception {
        var controller = new cn.diaojiahong.myyoutube.video.controller.InternalMediaDraftController(mediaDrafts, coordinator);
        var mvc = MockMvcBuilders.standaloneSetup(controller, new ContentDraftController(drafts, publications, coordinator, storage)).build();
        mvc.perform(post("/internal/video/draft/1/status/ready").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/contents/drafts/1/tasks")).andExpect(status().isNotFound());
    }
    @Test void equivalentStoredJsonDoesNotRestartReadyMediaPreparation() {
        var created = drafts.saveDynamic(null, 9L, dynamic()); ready(created.draftId());
        String stored = rows.selectById(created.draftId()).getMediaJson();
        var normalized = cn.diaojiahong.myyoutube.common.utils.JsonUtil.GSON.toJson(
                cn.diaojiahong.myyoutube.common.utils.JsonUtil.GSON.fromJson(stored, com.google.gson.JsonObject.class));
        jdbc.update("UPDATE media_draft SET media_json=? WHERE id=?", normalized.replace(",", ", "), created.draftId());
        var dto = dynamic(); dto.setTitle("只改标题");
        var edited = drafts.saveDynamic(created.draftId(), 9L, dto);
        assertEquals("ready", edited.status()); assertEquals(created.preparationId(), edited.preparationId());
        assertEquals(created.mediaVersion(), edited.mediaVersion()); assertNotNull(submitClaimed(publish, created.draftId(), 9L));
    }
    String reference(Long id, Long preparationId, long version) {
        return transaction.execute(tx -> new PreparationReferenceService(rows, contentRows).reference(
                new cn.diaojiahong.myyoutube.video.api.MediaDraftPort.PreparationReference(preparationId, id, 9L, version)));
    }
    @Test void persistentDraftAndContentReferencesSurviveSubmissionAndReleaseOnlyOnDeletion() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        assertEquals("RETAINED", reference(draft.draftId(), draft.preparationId(), 0));
        ready(draft.draftId()); submitClaimed(publish, draft.draftId(), 9L);
        assertEquals("RETAINED", reference(draft.draftId(), draft.preparationId(), 0));
        assertEquals("UNKNOWN", reference(draft.draftId(), draft.preparationId() + 1, 0));
        jdbc.update("UPDATE content SET is_delete=1 WHERE id=?", draft.draftId());
        assertEquals("RELEASED", reference(draft.draftId(), draft.preparationId(), 0));
        assertEquals("UNKNOWN", reference(-1L, draft.preparationId(), 0));
    }
    @Test void replacedVersionsReleaseOldCohortButKeepNewInputs() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        var replacement = dynamic(); replacement.getItems().get(1).setImageObjectKey("uploads/picture/9/20261011/new.png");
        var next = drafts.saveDynamic(draft.draftId(), 9L, replacement);
        assertEquals("RELEASED", reference(draft.draftId(), draft.preparationId(), 0));
        assertEquals("RETAINED", reference(next.draftId(), next.preparationId(), 1));
        assertEquals("UNKNOWN", reference(next.draftId(), next.preparationId(), 2));
    }
    @Test void sevenDayExpiryUsesUserDeadlineAndStopsReconciliationAndSubmission() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        jdbc.update("UPDATE media_draft SET expires_at=?,update_time=CURRENT_TIMESTAMP WHERE id=?", java.time.LocalDateTime.now().minusDays(1), created.draftId());
        new DraftExpiryService(rows, observation).expire();
        assertEquals(MediaDraftStatusEnum.EXPIRED, rows.selectById(created.draftId()).getStatus());
        assertEquals("RELEASED", reference(created.draftId(), created.preparationId(), 0));
        reconcileDue(); verifyNoInteractions(tasks);
        assertThrows(BizException.class, () -> submitClaimed(publish, created.draftId(), 9L));
        assertThrows(BizException.class, () -> drafts.saveDynamic(created.draftId(), 9L, dynamic()));
        assertFalse(coordinator.retry(created.draftId(), 9L));
    }
    @Test void userEditRenewsDeadlineAndSubmittedContentNeverExpires() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), draft.draftId());
        var metadata = new ContentMetadataDTO(); metadata.setTitle("继续编辑");
        drafts.saveMetadata(draft.draftId(), 9L, metadata);
        new DraftExpiryService(rows, observation).expire(); assertEquals(MediaDraftStatusEnum.PROCESSING, rows.selectById(draft.draftId()).getStatus());
        ready(draft.draftId()); submitClaimed(publish, draft.draftId(), 9L);
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), draft.draftId());
        new DraftExpiryService(rows, observation).expire(); assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(draft.draftId()).getStatus());
        assertEquals("RETAINED", reference(draft.draftId(), draft.preparationId(), 0));
    }

    ContentSubmissionService.Receipt submitClaimed(ContentSubmissionService worker, Long id, Long userId) {
        // 直接构造已受理和认领的记录，独立验证正式提交事务。
        var lease = java.time.LocalDateTime.now().plusSeconds(30).withNano(0);
        jdbc.update("UPDATE media_draft SET submission_state='pending',submission_revision=revision,submission_after=?,submission_deadline=? WHERE id=? AND submission_state='none' AND status<>'submitted'",
                lease, lease.plusMinutes(30), id);
        var draft = rows.selectById(id);
        return worker.submitRequested(id, userId, draft.getRevision(),
                draft.getSubmissionAfter() == null ? lease : draft.getSubmissionAfter());
    }

    Long readyPublicationVideo() {
        var id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/publication-"
                + com.baomidou.mybatisplus.core.toolkit.IdWorker.getId() + ".mp4", "publication.mp4");
        assertTrue(coordinator.uploaded(id)); ready(id);
        var metadata = new ContentMetadataDTO(); metadata.setTitle("视频");
        drafts.saveMetadata(id, 9L, metadata);
        clearInvocations(tasks);
        return id;
    }

    void scanPublications() { publications.scan(); }

    void publicationDue() {
        jdbc.update("UPDATE media_draft SET submission_after=? WHERE submission_state='pending'",
                java.time.LocalDateTime.now().minusSeconds(1).withNano(0));
        scanPublications();
    }

    @Test void publicationIsAcceptedWhilePreparingWithoutCallingModelsOrCreatingContent() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        var receipt = publications.request(draft.draftId(), 9L, draft.revision());
        assertEquals("pending", receipt.submissionState()); assertEquals("processing", receipt.draftStatus());
        assertNull(receipt.contentId()); assertNull(receipt.pipelineId());
        assertEquals(receipt, publications.request(draft.draftId(), 9L, draft.revision()));
        publicationDue();
        assertEquals(0, rows.selectById(draft.draftId()).getSubmissionAttempts());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(tasks, understanding, storage);
    }

    @Test void videoPublicationCanBeAcceptedBeforeUploadEventArrives() {
        var id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/new.mp4", "new.mp4");
        var metadata = new ContentMetadataDTO(); metadata.setTitle("视频");
        var saved = drafts.saveMetadata(id, 9L, metadata);
        assertEquals("uploading", publications.request(id, 9L, saved.revision()).draftStatus());
        publicationDue(); verifyNoInteractions(tasks, understanding, storage);
        assertTrue(coordinator.uploaded(id));
        ready(id); publicationDue();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
    }

    @Test void backgroundPublishesBothMediaTypesExactlyOnceAndDuplicateRequestReturnsRealReceipt() {
        for (boolean dynamic : new boolean[]{false, true}) {
            Long id = dynamic ? drafts.saveDynamic(null, 9L, dynamic()).draftId() : readyPublicationVideo();
            var draft = rows.selectById(id);
            publications.request(id, 9L, draft.getRevision());
            if (dynamic) ready(id);
            publicationDue(); publicationDue();
            var receipt = publications.request(id, 9L, draft.getRevision());
            assertEquals("submitted", receipt.submissionState());
            assertEquals(id, receipt.contentId()); assertEquals(100L, receipt.pipelineId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE id=?", Integer.class, id));
        }
        verify(understanding, times(2)).enroll(any(), any());
    }

    @Test void readyDraftWithoutPublicationIntentDoesNotPublish() {
        readyPublicationVideo(); scanPublications();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(understanding);
    }

    @Test void publicationChecksOwnerVersionAndTextBeforeAccepting() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        assertThrows(BizException.class, () -> publications.request(draft.draftId(), 10L, 0));
        assertThrows(BizException.class, () -> publications.request(draft.draftId(), 9L, null));
        assertThrows(BizException.class, () -> publications.request(draft.draftId(), 9L, 1));
        jdbc.update("UPDATE media_draft SET title='赌博' WHERE id=?", draft.draftId());
        assertThrows(BizException.class, () -> publications.request(draft.draftId(), 9L, 0));
        assertEquals(DraftSubmissionState.NONE, rows.selectById(draft.draftId()).getSubmissionState());
        verifyNoInteractions(tasks, understanding);
    }

    @Test void acceptedVersionRejectsAllEditsUntilCancelled() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        publications.request(draft.draftId(), 9L, 0);
        var metadata = new ContentMetadataDTO(); metadata.setTitle("修改");
        assertThrows(BizException.class, () -> drafts.saveMetadata(draft.draftId(), 9L, metadata));
        assertThrows(BizException.class, () -> drafts.saveDynamic(draft.draftId(), 9L, dynamic()));
        assertThrows(BizException.class, () -> publications.cancel(draft.draftId(), 10L, 0));
        assertThrows(BizException.class, () -> publications.cancel(draft.draftId(), 9L, 1));
        var cancelled = publications.cancel(draft.draftId(), 9L, 0);
        assertEquals("none", cancelled.submissionState()); assertEquals(1, cancelled.revision());
        assertThrows(BizException.class, () -> publications.request(draft.draftId(), 9L, 0));
        assertEquals("修改", drafts.saveMetadata(draft.draftId(), 9L, metadata).title());
        ready(draft.draftId()); publicationDue();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }

    @Test void staleWorkerCannotPublishAfterCancellation() {
        var id = readyPublicationVideo(); var original = rows.selectById(id);
        publications.request(id, 9L, original.getRevision());
        var claimed = rows.selectById(id);
        publications.cancel(id, 9L, original.getRevision());
        assertThrows(BizException.class, () -> publish.submitRequested(id, 9L, claimed.getRevision(), claimed.getSubmissionAfter()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(understanding);
    }

    @Test void cancellationDuringSnapshotValidationPreventsTransactionalSubmission() {
        var id = readyPublicationVideo(); var draft = rows.selectById(id);
        publications.request(id, 9L, draft.getRevision());
        var lease = rows.selectById(id).getSubmissionAfter();
        var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(ContentTypeEnum.VIDEO);
        when(strategy.prepare(any())).thenAnswer(call -> {
            var media = PreparedDrafts.media(call.getArgument(0));
            publications.cancel(id, 9L, draft.getRevision()); return media;
        });
        var worker = new ContentSubmissionService(mediaDrafts, contentRows, new ContentTypeStrategyRegistry(List.of(strategy)),
                understanding, transaction, new ContentSubmissionRules("content-moderation/blocked.txt"));
        assertThrows(BizException.class, () -> worker.submitRequested(id, 9L, draft.getRevision(), lease));
        verifyNoInteractions(understanding);
        assertEquals(DraftSubmissionState.NONE, rows.selectById(id).getSubmissionState());
    }

    @Test void temporarySubmissionFailureRollsBackThenRecoversWithoutPreparingAgain() {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        when(understanding.enroll(any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret"))
                .thenReturn(100L);
        publicationDue();
        var failed = rows.selectById(id);
        assertEquals(DraftSubmissionState.PENDING, failed.getSubmissionState()); assertEquals(1, failed.getSubmissionAttempts());
        assertFalse(failed.getSubmissionError().contains("secret"));
        assertEquals(MediaDraftStatusEnum.READY, failed.getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        publicationDue();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
        verifyNoInteractions(tasks);
    }

    @Test void boundedSubmissionRetriesBecomeVisibleAndExplicitRetryReusesReadyMedia() {
        var id = readyPublicationVideo(); var original = rows.selectById(id);
        publications.request(id, 9L, original.getRevision());
        when(understanding.enroll(any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        for (int i = 0; i < 4; i++) publicationDue();
        var failed = rows.selectById(id);
        assertEquals(DraftSubmissionState.FAILED, failed.getSubmissionState()); assertEquals(4, failed.getSubmissionAttempts());
        scanPublications(); verify(understanding, times(4)).enroll(any(), any());
        doReturn(100L).when(understanding).enroll(any(), any());
        publications.retry(id, 9L, original.getRevision()); publicationDue();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
        verifyNoInteractions(tasks);
    }

    @Test void leaseSurvivesWorkerRestartAndBecomesClaimableAfterExpiry() {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", java.time.LocalDateTime.now().plusMinutes(1), id);
        var restarted = new DraftPublicationService(rows, observation, publish, drafts, coordinator,
                new ContentSubmissionRules("content-moderation/blocked.txt"), properties, notifications, metrics);
        restarted.scan(); verifyNoInteractions(understanding);
        jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), id);
        restarted.scan();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
    }

    @Test void concurrentPublicationWorkersAndRequestsCreateOneContent() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision();
        CompletableFuture.allOf(CompletableFuture.runAsync(() -> publications.request(id, 9L, revision)),
                CompletableFuture.runAsync(() -> publications.request(id, 9L, revision))).join();
        CompletableFuture.allOf(CompletableFuture.runAsync(publications::scan),
                CompletableFuture.runAsync(publications::scan)).join();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, times(1)).enroll(any(), any());
    }

    @Test void preparationFailureRemainsEditableAfterCancellationAndDoesNotAutoSubmit() {
        var draft = drafts.saveDynamic(null, 9L, dynamic()); publications.request(draft.draftId(), 9L, 0);
        jdbc.update("UPDATE media_draft SET status='failed',error_message='准备失败' WHERE id=?", draft.draftId());
        publicationDue();
        assertEquals(DraftSubmissionState.FAILED, rows.selectById(draft.draftId()).getSubmissionState());
        publications.cancel(draft.draftId(), 9L, 0);
        var replacement = dynamic(); replacement.getItems().get(0).setImageObjectKey("uploads/picture/9/20261011/replacement.png");
        assertEquals("processing", drafts.saveDynamic(draft.draftId(), 9L, replacement).status());
        verifyNoInteractions(understanding);
    }

    @Test void preparationRetryWithChangedConfigurationRebindsIntentToServerRevision() {
        var draft = drafts.saveDynamic(null, 9L, dynamic()); publications.request(draft.draftId(), 9L, 0);
        when(tasks.preparation(draft.preparationId())).thenReturn(new PreparationContracts.View(
                draft.preparationId(), MediaTaskStatus.DEAD, 4, 3, "配置变化", null, true));
        reconcileDue(); publicationDue();
        assertEquals("pending", publications.retry(draft.draftId(), 9L, 0).submissionState());
        var renewed = rows.selectById(draft.draftId());
        assertEquals(renewed.getRevision(), renewed.getSubmissionRevision());
        assertEquals(1, renewed.getRevision());
        ready(draft.draftId()); publicationDue();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(draft.draftId()).getStatus());
    }

    @Test void expiredPublicationNeverCreatesContentEvenBeforeExpirySweep() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision();
        publications.request(id, 9L, revision);
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), id);
        publicationDue();
        assertEquals(DraftSubmissionState.FAILED, rows.selectById(id).getSubmissionState());
        assertThrows(BizException.class, () -> publications.retry(id, 9L, revision));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(understanding);
    }

    @Test void pendingQueryIsPaginatedAndOnlyReturnsOwnRequestedDrafts() {
        for (int i = 0; i < 3; i++) {
            var draft = drafts.saveDynamic(null, 9L, dynamic()); publications.request(draft.draftId(), 9L, 0);
        }
        drafts.saveDynamic(null, 9L, dynamic());
        var page = new cn.diaojiahong.myyoutube.common.page.PageQuery(); page.setPageSize(2);
        var result = publications.pending(9L, page);
        assertEquals(3, result.getTotal()); assertEquals(2, result.getPages()); assertEquals(2, result.getList().size());
        assertEquals(0, publications.pending(10L, page).getTotal());
        page.setPageNo(2); assertEquals(1, publications.pending(9L, page).getList().size());
        page.setPageSize(101); assertThrows(BizException.class, () -> publications.pending(9L, page));
    }

    @Test void httpSubmissionReturnsPendingAndProvidesRecoveryEndpoints() throws Exception {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        var mvc = MockMvcBuilders.standaloneSetup(new ContentDraftController(drafts, publications, coordinator, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/contents/drafts/{id}/submit", draft.draftId()).contentType("application/json").content("{\"revision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionState").value("pending"))
                .andExpect(jsonPath("$.data.pipelineId").isEmpty()).andExpect(jsonPath("$.data.contentId").isEmpty());
        mvc.perform(get("/contents/drafts/pending")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/contents/drafts/{id}/submission/cancel", draft.draftId()).contentType("application/json").content("{\"revision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionState").value("none"));
        verifyNoInteractions(understanding, tasks);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DraftSubmissionState.class)
    void submissionStateUsesLowercaseStorageAndTypedQueryParameters(DraftSubmissionState state) {
        var id = readyPublicationVideo();
        assertEquals(DraftSubmissionState.NONE, rows.selectById(id).getSubmissionState());
        jdbc.update("UPDATE media_draft SET submission_state=? WHERE id=?", state.getCode(), id);
        assertEquals(state, rows.selectById(id).getSubmissionState());
        assertEquals(1L, rows.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getId, id).eq(MediaDraft::getSubmissionState, state)));
    }

    @Test void httpDraftConflictsGiveConsistentCodesAndRecoveryMessages() throws Exception {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        publications.request(draft.draftId(), 9L, draft.revision());
        var mvc = MockMvcBuilders.standaloneSetup(new ContentDraftController(drafts, publications, coordinator, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/contents/drafts/{id}/metadata", draft.draftId()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("请先取消发布再编辑草稿"));
        mvc.perform(put("/contents/drafts/{id}/dynamic", draft.draftId()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("请先取消发布再编辑草稿"));
        assertEquals(draft.revision(), rows.selectById(draft.draftId()).getRevision());
        assertEquals(draft.title(), rows.selectById(draft.draftId()).getTitle());

        var failed = drafts.saveDynamic(null, 9L, dynamic());
        jdbc.update("UPDATE media_draft SET status='failed' WHERE id=?", failed.draftId());
        mvc.perform(post("/contents/drafts/{id}/submit", failed.draftId()).contentType("application/json").content("{\"revision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40903))
                .andExpect(jsonPath("$.message").value("媒体准备失败，请先重试媒体准备"));
        jdbc.update("UPDATE media_draft SET revision=1 WHERE id=?", failed.draftId());
        mvc.perform(post("/contents/drafts/{id}/submit", failed.draftId()).contentType("application/json").content("{\"revision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40904))
                .andExpect(jsonPath("$.message").value("草稿版本已变化，请刷新"));
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), failed.draftId());
        mvc.perform(post("/contents/drafts/{id}/submit", failed.draftId()).contentType("application/json").content("{\"revision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40905))
                .andExpect(jsonPath("$.message").value("草稿已过期，请重新上传"));
        assertEquals(DraftSubmissionState.NONE, rows.selectById(failed.draftId()).getSubmissionState());
        verifyNoInteractions(understanding, tasks);
    }

    @Test void submittedDraftCannotSuggestCancellingBeforeEditing() throws Exception {
        var draft = drafts.saveDynamic(null, 9L, dynamic()); ready(draft.draftId());
        publications.request(draft.draftId(), 9L, draft.revision()); publicationDue();
        var mvc = MockMvcBuilders.standaloneSetup(new ContentDraftController(drafts, publications, coordinator, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/contents/drafts/{id}/metadata", draft.draftId()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40906));
        mvc.perform(put("/contents/drafts/{id}/dynamic", draft.draftId()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40906));
        mvc.perform(post("/contents/drafts/{id}/submission/cancel", draft.draftId()).contentType("application/json").content("{\"revision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40906));
    }

    @Test void draftPreviewsSignOnlyOwnedDraftImages() throws Exception {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        when(storage.signGetUrl(anyString(), eq(1200L))).thenAnswer(call -> "https://storage.test/" + call.getArgument(0));
        var mvc = MockMvcBuilders.standaloneSetup(new ContentDraftController(drafts, publications, coordinator, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/contents/drafts/{id}/previews", draft.draftId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data['" + first + "']").value("https://storage.test/" + first));
        verify(storage).signGetUrl(first, 1200L); verify(storage).signGetUrl(second, 1200L);
        clearInvocations(storage);
        UserContext.setUser("10");
        mvc.perform(get("/contents/drafts/{id}/previews", draft.draftId())).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void publicationAcceptedDuringEditReportsTheCurrentLock(boolean dynamicEdit) {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        var racingRows = mock(MediaDraftMapper.class, org.mockito.AdditionalAnswers.delegatesTo(rows));
        var racingObservation = mock(DraftObservation.class, org.mockito.AdditionalAnswers.delegatesTo(observation));
        if (dynamicEdit) {
            doAnswer(call -> {
                publications.request(draft.draftId(), 9L, draft.revision());
                return observation.update(call.getArgument(0), call.getArgument(1));
            }).when(racingObservation).update(eq(draft.draftId()), any());
        } else {
            doAnswer(call -> {
                publications.request(draft.draftId(), 9L, draft.revision());
                return rows.update(null, call.getArgument(1));
            }).when(racingRows).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        }
        var editor = new ContentDraftService(racingRows, mediaPreparation, racingObservation,
                new ContentSubmissionRules("content-moderation/blocked.txt"));
        var error = assertThrows(BizException.class, () -> {
            if (dynamicEdit) editor.saveDynamic(draft.draftId(), 9L, dynamic());
            else { var metadata = new ContentMetadataDTO(); metadata.setTitle("更新"); editor.saveMetadata(draft.draftId(), 9L, metadata); }
        });
        assertEquals(ErrorCode.DRAFT_PUBLICATION_LOCKED, error.getErrorCode());
        assertEquals("请先取消发布再编辑草稿", error.getMessage());
        assertEquals(draft.title(), rows.selectById(draft.draftId()).getTitle());
        assertEquals(draft.revision(), rows.selectById(draft.draftId()).getRevision());
    }

    @Test void cancellationDuringPreparationRetryCannotRearmTheRequest() {
        var draft = drafts.saveDynamic(null, 9L, dynamic()); publications.request(draft.draftId(), 9L, 0);
        jdbc.update("UPDATE media_draft SET status='failed' WHERE id=?", draft.draftId()); publicationDue();
        when(tasks.preparation(draft.preparationId())).thenAnswer(call -> {
            publications.cancel(draft.draftId(), 9L, 0);
            return new PreparationContracts.View(draft.preparationId(), MediaTaskStatus.DEAD, 4, 3, "下载失败", null, false);
        });
        assertThrows(BizException.class, () -> publications.retry(draft.draftId(), 9L, 0));
        assertEquals(DraftSubmissionState.NONE, rows.selectById(draft.draftId()).getSubmissionState());
        reset(tasks);
        when(tasks.retryPreparation(anyLong())).thenReturn(true);
        ready(draft.draftId()); publicationDue();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }

    @Test void leaseReplacementDuringSnapshotValidationFencesStaleCommit() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision();
        publications.request(id, 9L, revision); var lease = rows.selectById(id).getSubmissionAfter();
        var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(ContentTypeEnum.VIDEO);
        when(strategy.prepare(any())).thenAnswer(call -> {
            var media = PreparedDrafts.media(call.getArgument(0));
            jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", lease.plusMinutes(1), id);
            return media;
        });
        var worker = new ContentSubmissionService(mediaDrafts, contentRows, new ContentTypeStrategyRegistry(List.of(strategy)),
                understanding, transaction, new ContentSubmissionRules("content-moderation/blocked.txt"));
        assertThrows(BizException.class, () -> worker.submitRequested(id, 9L, revision, lease));
        verifyNoInteractions(understanding);
        assertEquals(DraftSubmissionState.PENDING, rows.selectById(id).getSubmissionState());
    }

    @Test void expiredLeaseWithoutTakeoverCanCommitButCannotOutliveTotalDeadline() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision();
        publications.request(id, 9L, revision);
        var lease = java.time.LocalDateTime.now().minusSeconds(1).withNano(0);
        jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", lease, id);
        assertEquals(id, publish.submitRequested(id, 9L, revision, lease).contentId());
        publications.scan();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, times(1)).enroll(any(), any());

        var lateId = readyPublicationVideo(); var lateRevision = rows.selectById(lateId).getRevision();
        publications.request(lateId, 9L, lateRevision);
        jdbc.update("UPDATE media_draft SET submission_after=?,submission_deadline=? WHERE id=?", lease, lease, lateId);
        assertThrows(BizException.class, () -> publish.submitRequested(lateId, 9L, lateRevision, lease));
        publications.scan();
        assertEquals(DraftSubmissionState.FAILED, rows.selectById(lateId).getSubmissionState());
        assertEquals("发布超时，请重试或取消后编辑草稿", rows.selectById(lateId).getSubmissionError());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, times(1)).enroll(any(), any());
    }

    @Test void staleWorkerCannotBackoffOrFailTheReplacementLease() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision();
        publications.request(id, 9L, revision);
        var replacement = java.time.LocalDateTime.now().plusMinutes(2).withNano(0);
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(ContentTypeEnum.VIDEO);
        when(strategy.prepare(any())).thenAnswer(call -> {
            var media = PreparedDrafts.media(call.getArgument(0));
            if (replaced.compareAndSet(false, true))
                jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", replacement, id);
            return media;
        });
        var worker = new ContentSubmissionService(mediaDrafts, contentRows, new ContentTypeStrategyRegistry(List.of(strategy)),
                understanding, transaction, new ContentSubmissionRules("content-moderation/blocked.txt"));
        var service = new DraftPublicationService(rows, observation, worker, drafts, coordinator,
                new ContentSubmissionRules("content-moderation/blocked.txt"), properties, notifications, metrics);
        service.scan();
        var takenOver = rows.selectById(id);
        assertEquals(DraftSubmissionState.PENDING, takenOver.getSubmissionState());
        assertEquals(replacement, takenOver.getSubmissionAfter()); assertEquals(0, takenOver.getSubmissionAttempts());
        assertNull(takenOver.getSubmissionError()); assertNotNull(takenOver.getSubmissionDeadline());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(understanding);
        jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), id);
        service.scan(); service.scan();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, times(1)).enroll(any(), any());
    }

    @Test void postCommitNotificationFailureDoesNotTurnCommittedContentIntoFailedPublication() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision(); publications.request(id, 9L, revision);
        when(understanding.enroll(any(), any())).thenAnswer(call -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { throw new RuntimeException("MQ unavailable"); }
                    });
            return 100L;
        });
        publicationDue();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
        assertEquals(DraftSubmissionState.SUBMITTED, rows.selectById(id).getSubmissionState());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        publicationDue(); verify(understanding, times(1)).enroll(any(), any());
    }

    @Test void expirySweepWinsAgainstPendingRequestAndStopsPublication() {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), id);
        new DraftExpiryService(rows, observation).expire(); publicationDue();
        assertEquals(MediaDraftStatusEnum.EXPIRED, rows.selectById(id).getStatus());
        assertEquals(DraftSubmissionState.FAILED, rows.selectById(id).getSubmissionState()); verifyNoInteractions(understanding);
    }

    @Test void invalidFrozenMediaFailsOnceAndCanBeCancelledForEditing() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision(); publications.request(id, 9L, revision);
        jdbc.update("UPDATE media_draft SET prepared_json=NULL WHERE id=?", id); publicationDue();
        assertEquals(DraftSubmissionState.FAILED, rows.selectById(id).getSubmissionState()); assertEquals(1, rows.selectById(id).getSubmissionAttempts());
        scanPublications(); verifyNoInteractions(understanding);
        assertEquals("none", publications.cancel(id, 9L, revision).submissionState());
    }

    @Test void submissionCannotCommitAfterDeadlinePassesDuringMediaValidation() {
        var id = readyPublicationVideo(); var revision = rows.selectById(id).getRevision(); publications.request(id, 9L, revision);
        var lease = rows.selectById(id).getSubmissionAfter();
        var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(ContentTypeEnum.VIDEO);
        when(strategy.prepare(any())).thenAnswer(call -> {
            var media = PreparedDrafts.media(call.getArgument(0));
            jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), id);
            return media;
        });
        var worker = new ContentSubmissionService(mediaDrafts, contentRows, new ContentTypeStrategyRegistry(List.of(strategy)),
                understanding, transaction, new ContentSubmissionRules("content-moderation/blocked.txt"));
        assertThrows(BizException.class, () -> worker.submitRequested(id, 9L, revision, lease));
        verifyNoInteractions(understanding);
    }

    @Test void acceptedAndCurrentRevisionsRemainExplicitAfterSubmission() throws Exception {
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision();
        var accepted = publications.request(id, 9L, version);
        assertEquals(version, accepted.acceptedRevision()); assertEquals(version, accepted.currentRevision());
        publicationDue();
        var submitted = publications.request(id, 9L, version);
        assertEquals(version, submitted.acceptedRevision()); assertEquals(version + 1, submitted.currentRevision());
        var mvc = MockMvcBuilders.standaloneSetup(new ContentDraftController(drafts, publications, coordinator, storage))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/contents/drafts/{id}/submit", id).contentType("application/json")
                        .content("{\"revision\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.acceptedRevision").value(version))
                .andExpect(jsonPath("$.data.currentRevision").value(version + 1)).andExpect(jsonPath("$.data.revision").doesNotExist());
    }

    @Test void readingSubmittedReceiptsDoesNotIncrementSubmissionMetrics() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(publish);
        factory.addAspect(new cn.diaojiahong.myyoutube.common.monitoring.BusinessMetrics(registry));
        ContentSubmissionService observed = factory.getProxy();
        var service = new DraftPublicationService(rows, observation, observed, drafts, coordinator,
                new ContentSubmissionRules("content-moderation/blocked.txt"), properties, notifications, metrics);
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision();
        service.request(id, 9L, version);
        service.consume(new DraftPublicationMessage(id, version));
        double before = registry.find("myyoutube.business.operations").tag("operation", "submit_content")
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        assertEquals(1, before);
        for (int i = 0; i < 3; i++) assertEquals("submitted", service.request(id, 9L, version).submissionState());
        assertEquals(before, registry.find("myyoutube.business.operations").tag("operation", "submit_content")
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
    }

    @Test void expiredDraftCannotBeCancelledEvenBeforeExpirySweep() {
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision(); publications.request(id, 9L, version);
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), id);
        var error = assertThrows(BizException.class, () -> publications.cancel(id, 9L, version));
        assertEquals("草稿已过期，请重新上传", error.getMessage());
        assertEquals(DraftSubmissionState.PENDING, rows.selectById(id).getSubmissionState());
        new DraftExpiryService(rows, observation).expire();
        var expired = rows.selectById(id);
        assertEquals("草稿已过期，请重新上传", assertThrows(BizException.class,
                () -> publications.cancel(id, 9L, expired.getRevision())).getMessage());
    }

    @Test void nullExpiryUsesTheSameExpiredSemanticsForRequestAndCommit() {
        var id = readyPublicationVideo(); var draft = rows.selectById(id);
        jdbc.update("UPDATE media_draft SET expires_at=NULL WHERE id=?", id);
        assertThrows(BizException.class, () -> publications.request(id, 9L, draft.getRevision()));
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", draft.getExpiresAt(), id);
        publications.request(id, 9L, draft.getRevision());
        jdbc.update("UPDATE media_draft SET submission_after=?,expires_at=NULL WHERE id=?",
                java.time.LocalDateTime.now().plusSeconds(30), id);
        var invalid = rows.selectById(id);
        assertTrue(invalid.expiredAt(java.time.LocalDateTime.now()));
        assertFalse(mediaDrafts.submit(invalid, 1000L));
        verifyNoInteractions(understanding);
    }

    @Test void finalFreezeRequiresAnAcceptedIntentAndLeaseToken() {
        var id = readyPublicationVideo();
        assertFalse(mediaDrafts.submit(rows.selectById(id), 1000L));
        var version = rows.selectById(id).getRevision();
        publications.request(id, 9L, version);
        jdbc.update("UPDATE media_draft SET submission_after=NULL WHERE id=?", id);
        assertFalse(mediaDrafts.submit(rows.selectById(id), 1000L));
        jdbc.update("UPDATE media_draft SET submission_after=?,submission_deadline=NULL WHERE id=?",
                java.time.LocalDateTime.now().plusSeconds(30), id);
        assertFalse(mediaDrafts.submit(rows.selectById(id), 1000L));
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(understanding);
    }

    @Test void businessRejectionKeepsItsReasonButUnexpectedFailureDoesNotLeakDetails() {
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision(); publications.request(id, 9L, version);
        when(understanding.enroll(any(), any())).thenThrow(new BizException(ErrorCode.INVALID_ARGUMENT, "内容标题无效，请修改标题"));
        publicationDue();
        var failed = rows.selectById(id);
        assertEquals(DraftSubmissionState.FAILED, failed.getSubmissionState());
        assertEquals("内容标题无效，请修改标题", failed.getSubmissionError());
        assertEquals(1, failed.getSubmissionAttempts());
        publications.retry(id, 9L, version);
        doThrow(new IllegalArgumentException("internal secret")).when(understanding).enroll(any(), any());
        publicationDue();
        assertEquals(DraftSubmissionState.PENDING, rows.selectById(id).getSubmissionState());
        assertEquals(1, rows.selectById(id).getSubmissionAttempts());
        for (int i = 0; i < 3; i++) publicationDue();
        assertEquals(DraftSubmissionState.FAILED, rows.selectById(id).getSubmissionState());
        assertEquals(4, rows.selectById(id).getSubmissionAttempts());
        assertFalse(rows.selectById(id).getSubmissionError().contains("secret"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ErrorCode.class, names = {"STATE_CONFLICT", "RATE_LIMITED"})
    void retryableBusinessSignalsUseBackoffAndExhaustionBudget(ErrorCode code) {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        var deadline = rows.selectById(id).getSubmissionDeadline();
        when(understanding.enroll(any(), any())).thenThrow(new BizException(code, "internal secret"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var before = java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            publicationDue();
            var after = java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var pending = rows.selectById(id);
            int delay = List.of(5, 30, 120).get(attempt - 1);
            assertEquals(DraftSubmissionState.PENDING, pending.getSubmissionState());
            assertEquals(attempt, pending.getSubmissionAttempts()); assertEquals(deadline, pending.getSubmissionDeadline());
            assertFalse(pending.getSubmissionAfter().isBefore(before.plusSeconds(delay)));
            assertFalse(pending.getSubmissionAfter().isAfter(after.plusSeconds(delay)));
            assertEquals("内容提交暂时失败，系统将自动重试", pending.getSubmissionError());
            publications.scan(); publications.consume(new DraftPublicationMessage(id, pending.getSubmissionRevision()));
            verify(understanding, times(attempt)).enroll(any(), any());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        }
        publicationDue(); publications.scan();
        var failed = rows.selectById(id);
        assertEquals(DraftSubmissionState.FAILED, failed.getSubmissionState()); assertEquals(4, failed.getSubmissionAttempts());
        assertNull(failed.getSubmissionAfter()); assertNull(failed.getSubmissionDeadline());
        assertFalse(failed.getSubmissionError().contains("secret"));
        assertEquals(1, metrics.get("publication.failed.total").tag("reason", "error").counter().count());
        verify(understanding, times(4)).enroll(any(), any());
    }

    @Test void unexpectedIllegalStateRetriesAndThenRecoversWithoutPreparingAgain() {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        when(understanding.enroll(any(), any())).thenThrow(new IllegalStateException("internal secret")).thenReturn(100L);
        publicationDue();
        var pending = rows.selectById(id);
        assertEquals(DraftSubmissionState.PENDING, pending.getSubmissionState()); assertEquals(1, pending.getSubmissionAttempts());
        assertFalse(pending.getSubmissionError().contains("secret"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        publicationDue();
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verifyNoInteractions(tasks);
    }

    @Test void staleMessageCannotPublishNewVersionAfterCancellationAndReacceptance() {
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision(); publications.request(id, 9L, version);
        var edited = publications.cancel(id, 9L, version);
        publications.request(id, 9L, edited.revision()); clearInvocations(mq);
        publications.consume(new DraftPublicationMessage(id, version));
        verifyNoInteractions(understanding, mq);
        publications.consume(new DraftPublicationMessage(id, edited.revision()));
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
    }

    @Test void lostNotificationsDoNotAffectScannerAndAreOnlyCountedAsNotificationFailures() {
        doThrow(new RuntimeException("broker offline")).when(mq).asyncSend(anyString(), any(Object.class),
                any(org.apache.rocketmq.client.producer.SendCallback.class));
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision();
        assertEquals("pending", publications.request(id, 9L, version).submissionState());
        assertEquals(1, metrics.get("publication.notification.failed").counter().count());
        publications.scan();
        var completed = rows.selectById(id);
        assertEquals(MediaDraftStatusEnum.SUBMITTED, completed.getStatus());
        assertNull(completed.getSubmissionAfter()); assertNull(completed.getSubmissionDeadline());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }

    @Test void waitingOnlyMovesAfterAndMessagesDoNotRequeueOrTryBeforeDue() {
        var draft = drafts.saveDynamic(null, 9L, dynamic()); publications.request(draft.draftId(), 9L, 0);
        var deadline = rows.selectById(draft.draftId()).getSubmissionDeadline();
        clearInvocations(mq);
        var started = java.time.LocalDateTime.now().withNano(0);
        publications.scan();
        var waiting = rows.selectById(draft.draftId());
        assertEquals(deadline, waiting.getSubmissionDeadline()); assertEquals(0, waiting.getSubmissionAttempts());
        assertTrue(!waiting.getSubmissionAfter().isBefore(started.plusSeconds(15)));
        publications.scan(); publications.consume(new DraftPublicationMessage(draft.draftId(), 0));
        assertEquals(waiting.getSubmissionAfter(), rows.selectById(draft.draftId()).getSubmissionAfter());
        verifyNoInteractions(mq, understanding, tasks);
    }

    @Test void notificationIsAsyncAfterCommitAndDuplicateAcceptanceDoesNotSendAgainOrExtendDeadline() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        transaction.executeWithoutResult(tx -> {
            publications.request(draft.draftId(), 9L, 0);
            verifyNoInteractions(mq);
        });
        verify(mq).asyncSend(eq(MqConstants.TOPIC_DRAFT_PUBLICATION), eq(new DraftPublicationMessage(draft.draftId(), 0)),
                any(org.apache.rocketmq.client.producer.SendCallback.class));
        var deadline = rows.selectById(draft.draftId()).getSubmissionDeadline();
        clearInvocations(mq);
        publications.request(draft.draftId(), 9L, 0);
        assertEquals(deadline, rows.selectById(draft.draftId()).getSubmissionDeadline()); verifyNoInteractions(mq);
    }

    @Test void rolledBackAcceptanceDoesNotSendNotification() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        transaction.executeWithoutResult(tx -> {
            publications.request(draft.draftId(), 9L, 0); tx.setRollbackOnly();
        });
        assertEquals(DraftSubmissionState.NONE, rows.selectById(draft.draftId()).getSubmissionState());
        verifyNoInteractions(mq);
    }

    @Test void asyncCallbackFailureDoesNotChangeAcceptedIntent() {
        doAnswer(call -> {
            org.apache.rocketmq.client.producer.SendCallback callback = call.getArgument(2);
            callback.onException(new RuntimeException("network")); return null;
        }).when(mq).asyncSend(anyString(), any(Object.class), any(org.apache.rocketmq.client.producer.SendCallback.class));
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        assertEquals(DraftSubmissionState.PENDING, rows.selectById(id).getSubmissionState());
        assertEquals(1, metrics.get("publication.notification.failed").counter().count());
        publications.scan(); assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
    }

    @Test void totalDeadlineFailsPendingIntentAndExplicitRetrySetsNewDeadline() {
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision(); publications.request(id, 9L, version);
        jdbc.update("UPDATE media_draft SET submission_deadline=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), id);
        publications.scan();
        var failed = rows.selectById(id);
        assertEquals(DraftSubmissionState.FAILED, failed.getSubmissionState()); assertEquals("发布超时，请重试或取消后编辑草稿", failed.getSubmissionError());
        assertNull(failed.getSubmissionAfter()); assertNull(failed.getSubmissionDeadline());
        verifyNoInteractions(understanding);
        publications.scan();
        assertEquals(1, metrics.get("publication.failed.total").tag("reason", "deadline").counter().count());
        publications.retry(id, 9L, version);
        assertTrue(rows.selectById(id).getSubmissionDeadline().isAfter(java.time.LocalDateTime.now().plusMinutes(29)));
        publications.scan(); assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(id).getStatus());
    }

    @Test void deadlineSweepFailsCorruptFutureAfterAndMissingAfterWithoutSubmitting() {
        for (boolean missingAfter : new boolean[]{false, true}) {
            var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
            jdbc.update("UPDATE media_draft SET submission_after=?,submission_deadline=? WHERE id=?",
                    missingAfter ? null : java.time.LocalDateTime.now().plusDays(1), java.time.LocalDateTime.now().minusSeconds(1), id);
        }
        publications.scan(); verifyNoInteractions(understanding);
        properties.setBatchSize(1); publications.expireDeadlines();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft WHERE submission_state='failed' AND submission_after IS NULL AND submission_deadline IS NULL", Integer.class));
        assertEquals(2, metrics.get("publication.failed.total").tag("reason", "deadline").counter().count());
        publications.expireDeadlines();
        assertEquals(2, metrics.get("publication.failed.total").tag("reason", "deadline").counter().count());
        verifyNoInteractions(understanding);
    }

    @Test void retryBackoffDoesNotAttemptBeforeAfterAndKeepsTotalDeadline() {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        var deadline = rows.selectById(id).getSubmissionDeadline();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(understanding).enroll(any(), any());
        publications.scan();
        var retrying = rows.selectById(id);
        assertEquals(1, retrying.getSubmissionAttempts()); assertEquals(deadline, retrying.getSubmissionDeadline());
        publications.scan(); publications.consume(new DraftPublicationMessage(id, retrying.getSubmissionRevision()));
        verify(understanding, times(1)).enroll(any(), any());
        for (int i = 0; i < 3; i++) publicationDue();
        var failed = rows.selectById(id);
        assertEquals(DraftSubmissionState.FAILED, failed.getSubmissionState()); assertEquals(4, failed.getSubmissionAttempts());
        assertNull(failed.getSubmissionAfter()); assertNull(failed.getSubmissionDeadline());
    }

    @Test void submissionIsFencedWhenDeadlineOrLeaseChangesDuringMaterialValidation() {
        for (boolean deadline : new boolean[]{false, true}) {
            var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision(); publications.request(id, 9L, version);
            var lease = java.time.LocalDateTime.now().plusSeconds(30).withNano(0);
            jdbc.update("UPDATE media_draft SET submission_after=? WHERE id=?", lease, id);
            var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(ContentTypeEnum.VIDEO);
            when(strategy.prepare(any())).thenAnswer(call -> {
                var media = PreparedDrafts.media(call.getArgument(0));
                jdbc.update("UPDATE media_draft SET " + (deadline ? "submission_deadline" : "submission_after") + "=? WHERE id=?",
                        java.time.LocalDateTime.now().minusSeconds(1), id);
                return media;
            });
            var worker = new ContentSubmissionService(mediaDrafts, contentRows, new ContentTypeStrategyRegistry(List.of(strategy)),
                    understanding, transaction, new ContentSubmissionRules("content-moderation/blocked.txt"));
            assertThrows(BizException.class, () -> worker.submitRequested(id, 9L, version, lease));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        }
        verifyNoInteractions(understanding);
    }

    @Test void scannerHonorsBatchAndRetryConfiguration() {
        properties.setBatchSize(1); properties.setMaxAttempts(1); properties.setRetryBackoffSeconds(List.of(7));
        var first = readyPublicationVideo(); var second = readyPublicationVideo();
        publications.request(first, 9L, rows.selectById(first).getRevision()); publications.request(second, 9L, rows.selectById(second).getRevision());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(understanding).enroll(any(), any());
        publications.scan();
        assertEquals(1, jdbc.queryForObject("SELECT SUM(submission_attempts) FROM media_draft", Integer.class));
        publications.scan();
        assertEquals(2, jdbc.queryForObject("SELECT SUM(submission_attempts) FROM media_draft", Integer.class));
        publicationDue();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft WHERE submission_state='failed'", Integer.class));
    }

    @Test void scanFailureAndClaimCompetitionAreObservableWithoutCreatingContent() {
        var id = readyPublicationVideo(); publications.request(id, 9L, rows.selectById(id).getRevision());
        var competing = mock(MediaDraftMapper.class);
        when(competing.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(rows.selectById(id)));
        var service = new DraftPublicationService(competing, observation, publish, drafts, coordinator,
                new ContentSubmissionRules("content-moderation/blocked.txt"), properties, notifications, metrics);
        service.scan();
        assertEquals(1, metrics.get("publication.claim.missed").counter().count());
        when(competing.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, service::scan);
        assertEquals(1, metrics.get("publication.scan.failed").counter().count());
        assertEquals(2, metrics.get("publication.scan.duration").timer().count());
        verifyNoInteractions(understanding);
    }

    @Test void explicitRetryResetsAfterDeadlineAttemptsAndVisibleError() {
        var id = readyPublicationVideo(); var version = rows.selectById(id).getRevision(); publications.request(id, 9L, version);
        jdbc.update("UPDATE media_draft SET submission_after=?,submission_deadline=?,submission_attempts=2,submission_error='retry' WHERE id=?",
                java.time.LocalDateTime.now().plusMinutes(1), java.time.LocalDateTime.now().plusMinutes(5), id);
        clearInvocations(mq);
        publications.retry(id, 9L, version);
        var renewed = rows.selectById(id);
        assertEquals(0, renewed.getSubmissionAttempts()); assertNull(renewed.getSubmissionError());
        assertTrue(!renewed.getSubmissionAfter().isAfter(java.time.LocalDateTime.now()));
        assertTrue(renewed.getSubmissionDeadline().isAfter(java.time.LocalDateTime.now().plusMinutes(29)));
        verify(mq).asyncSend(eq(MqConstants.TOPIC_DRAFT_PUBLICATION), eq(new DraftPublicationMessage(id, version)),
                any(org.apache.rocketmq.client.producer.SendCallback.class));
    }

}
