package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts.*;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.transcode.api.AudioPreparationPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import cn.diaojiahong.myyoutube.video.controller.AudioController;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.service.AudioPreparationService;
import cn.diaojiahong.myyoutube.video.service.impl.AudioServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AudioLibraryIntegrationTest.Config.class)
class AudioLibraryIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = AudioMapper.class)
    static class Config {
        @Bean DataSource dataSource() { return ContentTestDatabase.create("audio-library"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds);
            var pagination = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.H2));
            bean.setPlugins(pagination); return bean.getObject();
        }
    }
    @Autowired AudioMapper rows;
    @Autowired DataSource database;
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final UserPort users = mock(UserPort.class);
    final AudioPreparationPort tasks = mock(AudioPreparationPort.class);
    final AudioProcessingProperties properties = new AudioProcessingProperties();
    AudioServiceImpl service;
    AudioPreparationService coordinator;
    JdbcTemplate jdbc;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(database);
        jdbc.execute("DROP TABLE IF EXISTS audio");
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `audio`");
        String sql = schema.substring(start, schema.indexOf(';', start) + 1);
        if (!Boolean.getBoolean("content.integration")) sql = sql.replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB", "");
        jdbc.execute(sql);
        reset(storage, users, tasks); UserContext.setUser("9");
        var user = new UserSummary(); user.setId(9L); user.setNickname("测试作者"); user.setAvatarUrl("avatars/9.jpg");
        when(users.listByIds(anyCollection())).thenReturn(List.of(user));
        when(storage.signGetUrl(anyString(), anyLong())).thenAnswer(c -> "signed/" + c.getArgument(0));
        service = new AudioServiceImpl(rows, storage, users, new ContentSubmissionRules("content-moderation/blocked.txt"), properties);
        coordinator = new AudioPreparationService(rows, tasks, properties);
    }
    @AfterEach void cleanup() { UserContext.removeUser(); }
    AudioPublishDTO upload(String key) {
        var dto = new AudioPublishDTO(); dto.setRequestId(key); dto.setObjectKey("uploads/audio/9/20261024/" + key + ".wav"); return dto;
    }
    long publish() { return Long.parseLong(service.publish(upload("request-1"))); }
    Request request(long id) { var row = rows.selectById(id); return new Request(id, 9L, row.getSourceObjectKey(), row.getSourceCoverObjectKey()); }
    Result prepared(long id) { var r = request(id); return new Result(id, 9L, r.prefix() + "source", r.coverObjectKey() == null ? null : r.prefix() + "cover.jpg", 1234, 4096, "audio/wav", r.coverObjectKey() == null ? 0 : 40, r.coverObjectKey() == null ? 0 : 30); }
    void approve(long id) { jdbc.update("UPDATE audio SET processing_status='ready',review_status='approved',object_key=?,duration_ms=1234 WHERE id=?", request(id).prefix() + "source", id); }

    @Test void admissionDoesNotReadStorageOrTrustClientDurationAndUsesNickname() {
        long id = publish(); var row = rows.selectById(id);
        assertEquals("测试作者 的音频", row.getTitle()); assertNull(row.getDurationMs()); assertNull(row.getObjectKey());
        assertEquals(AudioProcessingStatus.PROCESSING, row.getProcessingStatus()); assertEquals(AudioReviewStatus.PENDING, row.getReviewStatus());
        assertEquals("public", row.getVisibility()); verifyNoInteractions(storage, tasks);
    }
    @Test void replayUsesStableRequestEvenAfterNicknameChanges() {
        var dto = upload("retry"); String first = service.publish(dto); reset(users);
        assertEquals(first, service.publish(dto)); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audio", Integer.class));
        verifyNoInteractions(users, storage, tasks);
    }
    @Test void changedReplayCannotReplaceFileCoverOrName() {
        var dto = upload("same"); service.publish(dto);
        dto.setCoverObjectKey("uploads/picture/9/20261024/cover.jpg"); assertThrows(BizException.class, () -> service.publish(dto));
        dto.setCoverObjectKey(null); dto.setTitle("改名"); assertThrows(BizException.class, () -> service.publish(dto));
        dto.setTitle(null); dto.setObjectKey("uploads/audio/9/20261024/new.wav"); assertThrows(BizException.class, () -> service.publish(dto));
    }
    @Test void concurrentAdmissionCreatesOnlyOneAudio() {
        var first = CompletableFuture.supplyAsync(() -> { UserContext.setUser("9"); try { return service.publish(upload("concurrent")); } finally { UserContext.removeUser(); } });
        var second = CompletableFuture.supplyAsync(() -> { UserContext.setUser("9"); try { return service.publish(upload("concurrent")); } finally { UserContext.removeUser(); } });
        assertEquals(first.join(), second.join()); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audio", Integer.class));
    }
    @Test void rejectsCrossUserKeysUrlsAndWrongDirectories() {
        var dto = upload("bad");
        for (String key : List.of("uploads/audio/19/x/9/file.wav", "uploads/picture/9/file.wav", "https://example.com/uploads/audio/9/a.wav", "uploads/audio/9/../7/a.wav", "uploads/audio/9/" + "x".repeat(512))) {
            dto.setObjectKey(key); assertThrows(BizException.class, () -> service.publish(dto));
        }
        dto.setObjectKey("uploads/audio/9/day/file.wav"); dto.setCoverObjectKey("uploads/picture/7/day/cover.jpg");
        assertThrows(BizException.class, () -> service.publish(dto)); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audio", Integer.class));
    }
    @Test void validatesInitialTitleBeforeAdmission() {
        var dto = upload("bad-title"); dto.setTitle("x".repeat(101)); assertThrows(BizException.class, () -> service.publish(dto));
        dto.setTitle("赌博"); assertThrows(BizException.class, () -> service.publish(dto));
    }
    @Test void unfinishedAudioIsNotDiscoverableOrReferenceableAndNoSourcePreviewIsSigned() {
        long id = publish(); assertThrows(BizException.class, () -> service.getReferenceable(id, 9L));
        assertTrue(service.library(null, new PageQuery()).getList().isEmpty());
        var view = service.getMine(id); assertNull(view.getPreviewUrl()); assertEquals("signed/avatars/9.jpg", view.getCoverUrl());
        verify(storage, never()).signGetUrl(startsWith("uploads/"), anyLong());
    }
    @Test void readyStillNeedsApprovalAndPrivateApprovedIsOwnerOnly() {
        long id = publish(); jdbc.update("UPDATE audio SET processing_status='ready' WHERE id=?", id);
        assertThrows(BizException.class, () -> service.getReferenceable(id, 9L)); approve(id); service.setVisibility(id, "private");
        assertEquals(id, service.getReferenceable(id, 9L).getId()); assertThrows(BizException.class, () -> service.getReferenceable(id, 7L));
        assertTrue(service.library(null, new PageQuery()).getList().isEmpty());
    }
    @Test void completedProcessingPersistsServerMetadataAndWaitsForManualReview() {
        var dto = upload("cover"); dto.setCoverObjectKey("uploads/picture/9/day/cover.png");
        long id = Long.parseLong(service.publish(dto));
        when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.SUCCESS, 1, 0, prepared(id), null, null));
        coordinator.reconcile(); var row = rows.selectById(id);
        assertEquals(AudioProcessingStatus.READY, row.getProcessingStatus()); assertEquals(AudioReviewStatus.PENDING, row.getReviewStatus());
        assertEquals(1234, row.getDurationMs()); assertEquals(4096L, row.getFileSize()); assertNull(row.getProcessingDeadline());
        assertEquals(prepared(id).objectKey(), row.getObjectKey()); assertEquals(prepared(id).coverObjectKey(), row.getCoverObjectKey());
        assertTrue(service.library(null, new PageQuery()).getList().isEmpty());
    }
    @Test void mismatchedPreparedResultCannotPublishAnotherUsersFile() {
        long id = publish();
        when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.SUCCESS, 1, 0,
                new Result(id, 7L, "media-prepared/7/1/audio-source", null, 1234, 4096, "audio/wav", 0, 0), null, null));
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.PROCESSING, rows.selectById(id).getProcessingStatus()); assertNull(rows.selectById(id).getObjectKey());
    }
    @Test void deletionWhileWorkerReturnsDoesNotRestoreTheAudio() {
        long id = publish(); Result result = prepared(id);
        when(tasks.preparation(id)).thenAnswer(c -> { service.delete(id); return new View(id, MediaTaskStatus.SUCCESS, 1, 0, result, null, null); });
        coordinator.reconcile(); assertEquals(1, rows.selectById(id).getIsDelete()); assertNull(rows.selectById(id).getObjectKey());
        assertThrows(BizException.class, () -> service.getMine(id));
    }
    @Test void manualReviewChangeWhileWorkerReturnsIsNotOverwritten() {
        long id = publish(); Result result = prepared(id);
        when(tasks.preparation(id)).thenAnswer(c -> { jdbc.update("UPDATE audio SET review_status='banned',visibility='private' WHERE id=?", id); return new View(id, MediaTaskStatus.SUCCESS, 1, 0, result, null, null); });
        coordinator.reconcile(); var row = rows.selectById(id);
        assertEquals(AudioReviewStatus.BANNED, row.getReviewStatus()); assertEquals("private", row.getVisibility()); assertNull(row.getObjectKey());
    }
    @Test void oldLeaseCannotWriteAfterAnotherReconcilerClaimsTheAudio() {
        long id = publish(); Result result = prepared(id);
        when(tasks.preparation(id)).thenAnswer(c -> { jdbc.update("UPDATE audio SET preparation_after=DATEADD('SECOND',30,preparation_after) WHERE id=?", id); return new View(id, MediaTaskStatus.SUCCESS, 1, 0, result, null, null); });
        coordinator.reconcile(); assertNull(rows.selectById(id).getObjectKey());
    }
    @Test void infrastructureOutageIsRetriedByReconciliationAndEventuallyTimesOut() {
        long id = publish(); when(tasks.preparation(id)).thenThrow(new cn.diaojiahong.myyoutube.common.exception.DependencyException(cn.diaojiahong.myyoutube.common.result.ErrorCode.DEPENDENCY_FAILURE, "unavailable", null));
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.PROCESSING, rows.selectById(id).getProcessingStatus());
        jdbc.update("UPDATE audio SET processing_deadline=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP),preparation_after=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id=?", id);
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.FAILED, rows.selectById(id).getProcessingStatus());
        assertEquals("音频处理超时，请重试", rows.selectById(id).getErrorMessage());
    }
    @Test void technicalFailureDoesNotExposeInternalDetailsAndRetryIsDurable() {
        long id = publish(); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.DEAD, 4, 3, null, "internal-url-secret", "TASK_EXECUTION_FAILED"));
        coordinator.reconcile(); assertEquals("音频处理失败，请重试", service.getMine(id).getErrorMessage());
        assertTrue(service.retry(id)); assertTrue(rows.selectById(id).getPreparationRetry());
        when(tasks.retry(id)).thenReturn(true); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.SUCCESS, 5, 0, prepared(id), null, null));
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.READY, rows.selectById(id).getProcessingStatus());
        assertEquals(5, rows.selectById(id).getPreparationAttempt()); assertFalse(rows.selectById(id).getPreparationRetry());
        verify(tasks, never()).retry(id);
    }
    @Test void knownRejectionUsesMappedMessageAndUnknownCodesStayGeneric() {
        long id = publish();
        when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.DEAD, 1, 0, null, "internal secret",
                cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException.Reason.AUDIO_TOO_LONG.code()));
        coordinator.reconcile(); assertEquals("音频时长不能超过 10 分钟", service.getMine(id).getErrorMessage());
        assertTrue(service.retry(id));
        when(tasks.retry(id)).thenReturn(false);
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.FAILED, rows.selectById(id).getProcessingStatus());
        assertEquals("未能启动音频重试，请稍后重试", service.getMine(id).getErrorMessage());
        assertFalse(rows.selectById(id).getPreparationRetry()); assertNull(rows.selectById(id).getProcessingDeadline());
    }
    @Test void lookupRegistersOnlyMissingTasksAndDefersPollingToNextRound() {
        long id = publish(); coordinator.reconcile(); verify(tasks).preparation(id); verify(tasks).prepare(request(id));
        jdbc.update("UPDATE audio SET preparation_after=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), id);
        reset(tasks); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.RUNNING, 1, 0, null, null, null));
        coordinator.reconcile(); verify(tasks).preparation(id); verify(tasks, never()).prepare(any());
    }
    @Test void successfulRetryDoesNotConsumeAStaleDeadView() {
        long id = publish(); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.DEAD, 4, 3, null, null, "TASK_EXECUTION_FAILED"));
        coordinator.reconcile(); assertTrue(service.retry(id)); when(tasks.retry(id)).thenReturn(true);
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.PROCESSING, rows.selectById(id).getProcessingStatus());
        assertFalse(rows.selectById(id).getPreparationRetry());
        jdbc.update("UPDATE audio SET preparation_after=? WHERE id=?", java.time.LocalDateTime.now().minusSeconds(1), id);
        when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.SUCCESS, 5, 0, prepared(id), null, null));
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.READY, rows.selectById(id).getProcessingStatus());
    }
    @Test void staleAttemptCannotOverwriteRetry() {
        long id = publish(); jdbc.update("UPDATE audio SET preparation_attempt=5 WHERE id=?", id);
        when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.SUCCESS, 4, 0, prepared(id), null, null));
        coordinator.reconcile(); assertNull(rows.selectById(id).getObjectKey());
    }
    @Test void ownerManagementRejectsCrossUserAccessAndDeletedReplay() {
        long id = publish(); UserContext.setUser("7");
        assertThrows(BizException.class, () -> service.getMine(id)); assertThrows(BizException.class, () -> service.delete(id)); assertThrows(BizException.class, () -> service.retry(id));
        UserContext.setUser("9"); service.delete(id); assertThrows(BizException.class, () -> service.publish(upload("request-1")));
    }
    @Test void avatarFallbackIsBatchedAndCustomCoverTakesPrecedence() {
        long id = publish(); var dto = upload("second"); dto.setCoverObjectKey("uploads/picture/9/day/c.jpg");
        long second = Long.parseLong(service.publish(dto)); approve(id); approve(second);
        jdbc.update("UPDATE audio SET cover_object_key=? WHERE id=?", request(second).prefix() + "cover.jpg", second);
        clearInvocations(users); var result = service.myAudios(new PageQuery());
        assertEquals(2, result.getList().size()); verify(users, times(1)).listByIds(List.of(9L));
        assertEquals("signed/" + request(second).prefix() + "cover.jpg", result.getList().stream().filter(a -> a.getId().equals(Long.toString(second))).findFirst().orElseThrow().getCoverUrl());
    }
    @Test void httpAdmissionIsAcceptedAndClientDurationIsNotPersisted() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AudioController(service)).build();
        mvc.perform(post("/audios/publish").contentType("application/json").content("{\"requestId\":\"http\",\"objectKey\":\"uploads/audio/9/day/a.wav\",\"durationMs\":1}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data").isString());
        assertNull(jdbc.queryForObject("SELECT duration_ms FROM audio", Integer.class));
        long id = jdbc.queryForObject("SELECT id FROM audio", Long.class);
        mvc.perform(get("/audios/" + id)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/audios/mine/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.processingStatus").value("processing"))
                .andExpect(jsonPath("$.data.reviewStatus").value("pending"));
    }
}
