package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.strategy.*;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.api.enums.*;
import cn.diaojiahong.myyoutube.video.chain.dynamic.*;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ContentDraftIntegrationTest.Config.class)
class ContentDraftIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = {MediaDraftMapper.class, ContentMapper.class})
    static class Config {
        @Bean DataSource dataSource() { return ContentTestDatabase.create("content-drafts"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds); return bean.getObject();
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
        @Bean MediaTaskPort tasks() { return mock(MediaTaskPort.class); }
        @Bean ContentUnderstandingService understanding() { return mock(ContentUnderstandingService.class); }
        @Bean DynamicMediaPreparationService preparation(S3StorageUtil storage, IAudioService audio) {
            return new DynamicMediaPreparationService(new SyncChainStepRegistry(List.of(
                    new ItemsCountStep(), new CanvasStyleStep(), new AudioReferenceStep(audio), new ItemsPrepareStep())));
        }
        @Bean ContentDraftService drafts(MediaDraftMapper rows, DynamicMediaPreparationService preparation, cn.diaojiahong.myyoutube.video.service.DraftObservation observation) {
            return new ContentDraftService(rows, preparation, observation);
        }
        @Bean cn.diaojiahong.myyoutube.video.service.DraftPreparationService coordinator(MediaDraftMapper rows, MediaTaskPort tasks, cn.diaojiahong.myyoutube.video.service.DraftObservation observation) {
            return new cn.diaojiahong.myyoutube.video.service.DraftPreparationService(rows, tasks, observation);
        }
        @Bean MediaDraftService mediaDrafts(MediaDraftMapper rows, cn.diaojiahong.myyoutube.video.service.DraftObservation observation) { return new MediaDraftService(rows, observation); }
        @Bean ContentSubmissionService publish(MediaDraftService drafts, MediaTaskPort tasks, VideoMapper videos,
                                            DynamicMapper dynamics, DynamicMediaPreparationService preparation,
                                            ContentMapper contents, ContentUnderstandingService understanding, TransactionTemplate tx) {
            var strategies = new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos), new DynamicContentStrategy(dynamics)));
            return new ContentSubmissionService(drafts, contents, strategies, understanding, tx);
        }
    }

    @Autowired DataSource dataSource;
    @Autowired cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties observations;
    @Autowired cn.diaojiahong.myyoutube.video.service.DraftObservation observation;
    @Autowired ContentDraftService drafts;
    @Autowired cn.diaojiahong.myyoutube.video.service.DraftPreparationService coordinator;
    @Autowired ContentMapper contentRows;
    @Autowired TransactionTemplate transaction;
    @Autowired MediaDraftService mediaDrafts;
    @Autowired MediaDraftMapper rows;
    @Autowired ContentSubmissionService publish;
    @Autowired MediaTaskPort tasks;
    @Autowired ContentUnderstandingService understanding;
    @Autowired S3StorageUtil storage;
    @Autowired IAudioService audio;
    JdbcTemplate jdbc;
    final String first = "uploads/picture/9/20261011/a.png", second = "uploads/picture/9/20261011/b.png";

    @BeforeEach void schema() throws Exception {
        reset(tasks, understanding, storage, audio); observations.getRecording().setEnabled(false);
        UserContext.setUser("9");
        jdbc = new JdbcTemplate(dataSource);
        ContentTestDatabase.outbox(jdbc);
        ContentTestDatabase.drop(jdbc,"media_draft","video","dynamic","content");
        jdbc.execute("""
                CREATE TABLE media_draft(event_sequence BIGINT NOT NULL DEFAULT 0,id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,type VARCHAR(16) NOT NULL,
                  title VARCHAR(100),description VARCHAR(512),visibility VARCHAR(16) DEFAULT 'public',allow_comment INT DEFAULT 1,
                  media_version BIGINT DEFAULT 0,preparation_id BIGINT,preparation_attempt INT DEFAULT 0,preparation_retry BOOLEAN DEFAULT FALSE,
                  expires_at TIMESTAMP,preparation_after TIMESTAMP,prepared_json TEXT,prepared_cover_object_key VARCHAR(512),
                  revision INT DEFAULT 0,cover_object_key VARCHAR(512),media_json TEXT,object_key VARCHAR(512) UNIQUE,original_filename VARCHAR(256),status VARCHAR(16),
                  duration_ms BIGINT,error_message VARCHAR(500),create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE video(id BIGINT PRIMARY KEY,object_key VARCHAR(512),duration_ms BIGINT)");
        jdbc.execute("CREATE TABLE dynamic(id BIGINT PRIMARY KEY,media_json TEXT)");
        jdbc.execute("CREATE TABLE content(id BIGINT PRIMARY KEY,type VARCHAR(16),author_id BIGINT,title VARCHAR(100),description TEXT,cover_object_key VARCHAR(512),category_code VARCHAR(32),tags TEXT,allow_comment INT,region VARCHAR(32),is_delete INT DEFAULT 0,status VARCHAR(16),visibility VARCHAR(16),play_count BIGINT DEFAULT 0,like_count BIGINT DEFAULT 0,collect_count BIGINT DEFAULT 0,comment_count BIGINT DEFAULT 0,share_count BIGINT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
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
        when(audio.getReferenceable(20L, 9L)).thenThrow(new BizException(BizException.BIZ_ERROR, "无权使用该音频"));
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

    void reconcileDue() {
        jdbc.update("UPDATE media_draft SET preparation_after=? WHERE status='processing'", java.time.LocalDateTime.now().minusSeconds(1));
        coordinator.reconcile();
    }

    void ready(Long id) {
        var row = rows.selectById(id);
        var request = PreparedDraftTestSupport.request(row);
        when(tasks.preparation(row.getPreparationId())).thenReturn(new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(
                row.getPreparationId(), "SUCCESS", 1, 0, null, PreparedDraftTestSupport.manifest(request), false));
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
        assertThrows(IllegalStateException.class, () -> publish.submit(id, 9L));
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(sequence, rows.selectById(id).getEventSequence());
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
    }

    @Test void concurrentSubmissionCreatesOneContentAndOneEnrollment() {
        Long id = readyVideo();
        var dto = new ContentMetadataDTO(); dto.setTitle("视频");
        drafts.saveMetadata(id, 9L, dto);
        var a = CompletableFuture.supplyAsync(() -> publish.submit(id, 9L));
        var b = CompletableFuture.supplyAsync(() -> publish.submit(id, 9L));
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
        assertThrows(IllegalStateException.class, () -> publish.submit(id, 9L));
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM video", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }

    @Test void nativeSubmissionFreezesMediaAndHasNoTranscodeDependency() {
        var dto = dynamic(); dto.setCoverImageObjectKey(second); dto.setAllowComment(0);
        var draft = drafts.saveDynamic(null, 9L, dto);
        ready(draft.draftId()); clearInvocations(tasks);
        var firstReceipt = publish.submit(draft.draftId(), 9L);
        assertEquals(firstReceipt, publish.submit(draft.draftId(), 9L));
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
    @Test void editDuringMaterialValidationCannotSubmitOldSnapshot() {
        Long id = readyVideo();
        var metadata = new ContentMetadataDTO(); metadata.setTitle("初始"); drafts.saveMetadata(id, 9L, metadata);
        var strategy = mock(cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategy.class);
        when(strategy.type()).thenReturn(cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum.VIDEO);
        when(strategy.prepare(any())).thenAnswer(call -> {
            var media = cn.diaojiahong.myyoutube.content.strategy.PreparedDrafts.media(call.getArgument(0));
            metadata.setTitle("更新"); drafts.saveMetadata(id, 9L, metadata); return media;
        });
        var racing = new ContentSubmissionService(mediaDrafts, contentRows,
                new ContentTypeStrategyRegistry(List.of(strategy)), understanding, transaction);
        assertThrows(BizException.class, () -> racing.submit(id, 9L));
        assertEquals("更新", rows.selectById(id).getTitle());
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, never()).enroll(any(), any());
    }
    @Test void nativeSubmissionUsesFrozenMediaAfterAudioPermissionsChange() {
        var dto = dynamic(); dto.setAudioId(20L);
        when(audio.getReferenceable(20L, 9L)).thenReturn(new Audio().setId(20L).setObjectKey("uploads/audio/7/20261011/music.mp3").setDurationMs(2000));
        var draft = drafts.saveDynamic(null, 9L, dto);
        ready(draft.draftId()); clearInvocations(audio, storage, tasks);
        when(audio.getReferenceable(20L, 9L)).thenThrow(new BizException(BizException.BIZ_ERROR,"已移除音频权限"));
        assertNotNull(publish.submit(draft.draftId(), 9L));
        assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(draft.draftId()).getStatus());
        verifyNoInteractions(audio, storage, tasks);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }
    @Test void processingDraftCannotSubmitAndMetadataEditsDoNotInvalidatePreparation() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        assertThrows(BizException.class, () -> publish.submit(created.draftId(), 9L));
        var metadata = new ContentMetadataDTO(); metadata.setTitle("新标题");
        var edited = drafts.saveMetadata(created.draftId(), 9L, metadata);
        assertEquals(created.preparationId(), edited.preparationId()); assertEquals(created.mediaVersion(), edited.mediaVersion());
        ready(created.draftId());
        var ready = drafts.get(created.draftId(), 9L);
        var unchanged = dynamic(); unchanged.setTitle("再改标题");
        var saved = drafts.saveDynamic(created.draftId(), 9L, unchanged);
        assertEquals("ready", saved.status()); assertEquals(ready.preparationId(), saved.preparationId());
        assertEquals(ready.mediaVersion(), saved.mediaVersion());
        assertNotNull(publish.submit(created.draftId(), 9L));
        verifyNoInteractions(storage);
    }

    @Test void oldPreparationResponseCannotReadyReplacementMedia() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var row = rows.selectById(created.draftId());
        var request = PreparedDraftTestSupport.request(row);
        when(tasks.preparation(created.preparationId())).thenAnswer(call -> {
            var replacement = dynamic(); replacement.getItems().get(1).setImageObjectKey("uploads/picture/9/20261011/replaced.png");
            drafts.saveDynamic(created.draftId(), 9L, replacement);
            return new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(created.preparationId(), "SUCCESS", 1, 0, null,
                    PreparedDraftTestSupport.manifest(request), false);
        });
        reconcileDue();
        var current = rows.selectById(created.draftId());
        assertEquals(MediaDraftStatusEnum.PROCESSING, current.getStatus()); assertNull(current.getPreparedJson());
        assertEquals(1, current.getMediaVersion()); assertNotEquals(created.preparationId(), current.getPreparationId());
        assertThrows(BizException.class, () -> publish.submit(created.draftId(), 9L));
        ready(created.draftId()); assertNotNull(publish.submit(created.draftId(), 9L));
    }

    @Test void deadTaskRetryRejectsLateFailureAndKeepsSamePreparation() {
        var created = drafts.saveDynamic(null, 9L, dynamic());
        var row = rows.selectById(created.draftId()); var request = PreparedDraftTestSupport.request(row);
        var dead = new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(created.preparationId(), "DEAD", 4, 3, "下载失败", null, false);
        when(tasks.preparation(created.preparationId())).thenReturn(dead);
        reconcileDue(); assertEquals("failed", drafts.get(created.draftId(), 9L).status());
        assertTrue(coordinator.retry(created.draftId(), 9L)); assertTrue(coordinator.retry(created.draftId(), 9L));
        when(tasks.retryPreparation(created.preparationId())).thenReturn(true);
        reconcileDue(); assertEquals("processing", drafts.get(created.draftId(), 9L).status());
        assertEquals(5, rows.selectById(created.draftId()).getPreparationAttempt());
        when(tasks.preparation(created.preparationId())).thenReturn(new cn.diaojiahong.myyoutube.common.media.PreparationContracts.View(
                created.preparationId(), "SUCCESS", 5, 0, null, PreparedDraftTestSupport.manifest(request), false));
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
                before.getPreparationId(), "DEAD", 4, 3, PreparationContracts.PARAMETERS_CHANGED_MESSAGE, null, true));
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
        var dead = new PreparationContracts.View(created.preparationId(), "DEAD", 4, 3, "旧配置", null, true);
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
                created.preparationId(), "DEAD", 4, 3, "下载失败", null, false));
        reconcileDue();
        when(tasks.preparation(created.preparationId())).thenReturn(null);
        assertThrows(BizException.class, () -> coordinator.retry(created.draftId(), 9L));
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
                created.preparationId(), "SUCCESS", 1, 0, null, incomplete, false));
        reconcileDue(); assertEquals("processing", drafts.get(created.draftId(), 9L).status());
        assertNull(rows.selectById(created.draftId()).getPreparedJson());
        assertThrows(BizException.class, () -> publish.submit(created.draftId(), 9L));
        ready(created.draftId());
        jdbc.update("UPDATE media_draft SET media_version=media_version+1 WHERE id=?", created.draftId());
        assertThrows(BizException.class, () -> publish.submit(created.draftId(), 9L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }
    @Test void unversionedLegacyStatusCallbackCannotReadyPreparedDraft() {
        Long id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/callback.mp4", "callback.mp4");
        assertTrue(coordinator.uploaded(id)); assertFalse(coordinator.uploaded(id));
        var notifications = mock(cn.diaojiahong.myyoutube.notification.service.NotificationPublisher.class);
        var controller = new cn.diaojiahong.myyoutube.video.controller.InternalMediaDraftController(mediaDrafts, notifications, coordinator);
        assertFalse(controller.updateStatus(id, "ready", new cn.diaojiahong.myyoutube.video.api.MediaDraftPort.StatusUpdate(1000L, null)));
        assertEquals(MediaDraftStatusEnum.PROCESSING, rows.selectById(id).getStatus());
        assertNull(rows.selectById(id).getPreparedJson()); verifyNoInteractions(notifications);
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
        assertEquals(created.mediaVersion(), edited.mediaVersion()); assertNotNull(publish.submit(created.draftId(), 9L));
    }
    String reference(Long id, Long preparationId, long version) {
        return transaction.execute(tx -> new PreparationReferenceService(rows, contentRows).reference(
                new cn.diaojiahong.myyoutube.video.api.MediaDraftPort.PreparationReference(preparationId, id, 9L, version)));
    }
    @Test void persistentDraftAndContentReferencesSurviveSubmissionAndReleaseOnlyOnDeletion() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        assertEquals("RETAINED", reference(draft.draftId(), draft.preparationId(), 0));
        ready(draft.draftId()); publish.submit(draft.draftId(), 9L);
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
        assertThrows(BizException.class, () -> publish.submit(created.draftId(), 9L));
        assertThrows(BizException.class, () -> drafts.saveDynamic(created.draftId(), 9L, dynamic()));
        assertFalse(coordinator.retry(created.draftId(), 9L));
    }
    @Test void userEditRenewsDeadlineAndSubmittedContentNeverExpires() {
        var draft = drafts.saveDynamic(null, 9L, dynamic());
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), draft.draftId());
        var metadata = new ContentMetadataDTO(); metadata.setTitle("继续编辑");
        drafts.saveMetadata(draft.draftId(), 9L, metadata);
        new DraftExpiryService(rows, observation).expire(); assertEquals(MediaDraftStatusEnum.PROCESSING, rows.selectById(draft.draftId()).getStatus());
        ready(draft.draftId()); publish.submit(draft.draftId(), 9L);
        jdbc.update("UPDATE media_draft SET expires_at=? WHERE id=?", java.time.LocalDateTime.now().minusDays(1), draft.draftId());
        new DraftExpiryService(rows, observation).expire(); assertEquals(MediaDraftStatusEnum.SUBMITTED, rows.selectById(draft.draftId()).getStatus());
        assertEquals("RETAINED", reference(draft.draftId(), draft.preparationId(), 0));
    }

}
