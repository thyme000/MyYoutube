package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.search.api.AudioSearchPort;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.media.AudioAnalysisContracts;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import cn.diaojiahong.myyoutube.common.media.ContentArtifactStore;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.video.mapper.AudioReviewRecordMapper;
import cn.diaojiahong.myyoutube.video.domain.po.AudioReviewRecord;
import cn.diaojiahong.myyoutube.video.service.AudioReviewService;
import cn.diaojiahong.myyoutube.video.service.AudioIndexSyncService;
import cn.diaojiahong.myyoutube.video.controller.InternalAdminAudioController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
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
import cn.diaojiahong.myyoutube.video.controller.InternalAudioDocumentController;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
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
            var pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            bean.setPlugins(pagination); return bean.getObject();
        }
    }
    @Autowired AudioMapper rows;
    @Autowired AudioReviewRecordMapper reviewRecords;
    final AudioSearchPort search = mock(AudioSearchPort.class);
    AudioReviewService reviews;
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
        jdbc.execute("DROP TABLE IF EXISTS audio_review_record");
        int auditStart = schema.indexOf("CREATE TABLE IF NOT EXISTS `audio_review_record`");
        String auditSql = schema.substring(auditStart, schema.indexOf(';', auditStart) + 1);
        if (!Boolean.getBoolean("content.integration")) auditSql = auditSql.replace(" ENGINE=InnoDB", "");
        jdbc.execute(auditSql);
        reset(storage, users, tasks, search);
        when(search.search(any())).thenReturn(new AudioDiscoveryContracts.Slice(List.of(), false)); UserContext.setUser("9");
        var user = new UserSummary(); user.setId(9L); user.setNickname("测试作者"); user.setAvatarUrl("avatars/9.jpg");
        when(users.listByIds(anyCollection())).thenReturn(List.of(user));
        when(storage.signGetUrl(anyString(), anyLong())).thenAnswer(c -> "signed/" + c.getArgument(0));
        service = new AudioServiceImpl(rows, storage, users, new ContentSubmissionRules("content-moderation/blocked.txt"), properties, search);
        reviews = new AudioReviewService(rows, reviewRecords,
                new TransactionTemplate(new DataSourceTransactionManager(database)));
        coordinator = new AudioPreparationService(rows, tasks, properties, reviews);
    }
    @AfterEach void cleanup() { UserContext.removeUser(); }
    AudioPublishDTO upload(String key) {
        var dto = new AudioPublishDTO(); dto.setRequestId(key); dto.setObjectKey("uploads/audio/9/20261024/" + key + ".wav"); return dto;
    }
    long publish() { return Long.parseLong(service.publish(upload("request-1"))); }

    private AudioServiceImpl transactionalService() {
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database), new AnnotationTransactionAttributeSource()));
        return (AudioServiceImpl) factory.getProxy();
    }

    @Test void submissionAudioLockRequiresAnExistingTransaction() {
        long id = publish(); approve(id);
        assertThrows(IllegalTransactionStateException.class, () -> transactionalService().lockReferenceable(id, 9L));
    }

    @ParameterizedTest @CsvSource({"public,7,true", "private,9,true", "private,7,false"})
    void lockedAudioUsesTheSameOwnershipRulesAsDraftSelection(String visibility, long author, boolean allowed) {
        long id = publish(); approve(id); service.setVisibility(id, visibility);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        var locked = transactionalService();
        if (allowed) assertEquals(id, transaction.execute(tx -> locked.lockReferenceable(id, author)).getId());
        else assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> transaction.execute(tx -> locked.lockReferenceable(id, author))).getErrorCode());
    }

    @ParameterizedTest @CsvSource({"visibility='private',FORBIDDEN", "is_delete=1,NOT_FOUND", "review_status='banned',INVALID_ARGUMENT"})
    void submissionWaitingForAudioLockChecksTheCommittedMutation(String mutation, ErrorCode expected) throws Exception {
        long id = publish(); approve(id);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        var locked = transactionalService();
        var entered = new CountDownLatch(1); var waiting = new AtomicReference<CompletableFuture<ErrorCode>>();
        transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE audio SET " + mutation + " WHERE id=?", id);
            waiting.set(CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                try { transaction.execute(inner -> locked.lockReferenceable(id, 7L)); return null; }
                catch (BizException error) { return error.getErrorCode(); }
            }));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            assertThrows(TimeoutException.class, () -> waiting.get().get(200, TimeUnit.MILLISECONDS));
        });
        assertEquals(expected, waiting.get().get(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest @ValueSource(strings = {"visibility='private'", "is_delete=1", "review_status='banned'"})
    void successfulSubmissionHoldsAudioLockUntilTheOuterTransactionCommits(String mutation) throws Exception {
        long id = publish(); approve(id);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        var locked = transactionalService();
        var entered = new CountDownLatch(1); var waiting = new AtomicReference<CompletableFuture<Integer>>();
        transaction.executeWithoutResult(tx -> {
            assertEquals(id, locked.lockReferenceable(id, 7L).getId());
            waiting.set(CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                return jdbc.update("UPDATE audio SET " + mutation + " WHERE id=?", id);
            }));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            assertThrows(TimeoutException.class, () -> waiting.get().get(200, TimeUnit.MILLISECONDS));
        });
        assertEquals(1, waiting.get().get(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest @CsvSource({"0,10", "1,101", "100,100", "2147483647,100"})
    void invalidSearchBoundsAreRejectedBeforeCallingTheSearchPort(int pageNo, int pageSize) {
        var page = new PageQuery(); page.setPageNo(pageNo); page.setPageSize(pageSize);
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BizException.class, () -> service.library(null, page)).getErrorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BizException.class, () -> service.searchMine(null, page)).getErrorCode());
        verifyNoInteractions(search);
    }

    @Test void indexTimestampUsesBusinessTimeWithoutChangingStoredCreateTime() {
        long id = publish();
        var wallTime = LocalDateTime.parse("2026-10-25T10:00:00");
        jdbc.update("UPDATE audio SET create_time=? WHERE id=?", wallTime, id);
        var documents = new InternalAudioDocumentController(rows);
        assertEquals(Instant.parse("2026-10-25T02:00:00Z").toEpochMilli(), documents.document(id).createdAt());
        assertEquals(wallTime, rows.selectById(id).getCreateTime());
    }
    Request request(long id) { var row = rows.selectById(id); return new Request(id, 9L, row.getSourceObjectKey(), row.getSourceCoverObjectKey()); }
    Result prepared(long id) { var r = request(id); return new Result(id, 9L, r.prefix() + "source", r.coverObjectKey() == null ? null : r.prefix() + "cover.jpg", 1234, 4096, "audio/wav", r.coverObjectKey() == null ? 0 : 40, r.coverObjectKey() == null ? 0 : 30); }
    void approve(long id) { jdbc.update("UPDATE audio SET processing_status='ready',review_status='approved',object_key=?,duration_ms=1234 WHERE id=?", request(id).prefix() + "source", id); }

    long manualReviewable() {
        long id = publish();
        jdbc.update("UPDATE audio SET processing_status='ready',analysis_status='manual',object_key=?,duration_ms=1234,preparation_after=NULL,processing_deadline=NULL WHERE id=?",
                request(id).prefix() + "source", id);
        return id;
    }

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
        assertEquals(1234, row.getDurationMs()); assertEquals(4096L, row.getFileSize()); assertNotNull(row.getProcessingDeadline()); assertEquals(AudioAnalysisStatus.RECOGNIZING, row.getAnalysisStatus());
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
        long id = publish(); when(tasks.preparation(id)).thenThrow(new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "unavailable", null));
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
                InvalidMediaInputException.Reason.AUDIO_TOO_LONG.code()));
        coordinator.reconcile(); assertEquals("音频时长不能超过 10 分钟", service.getMine(id).getErrorMessage());
        assertTrue(service.retry(id));
        when(tasks.retry(id)).thenReturn(false);
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.FAILED, rows.selectById(id).getProcessingStatus());
        assertEquals("未能启动音频重试，请稍后重试", service.getMine(id).getErrorMessage());
        assertFalse(rows.selectById(id).getPreparationRetry()); assertNull(rows.selectById(id).getProcessingDeadline());
    }
    @Test void lookupRegistersOnlyMissingTasksAndDefersPollingToNextRound() {
        long id = publish(); coordinator.reconcile(); verify(tasks).preparation(id); verify(tasks).prepare(request(id));
        jdbc.update("UPDATE audio SET preparation_after=? WHERE id=?", LocalDateTime.now().minusSeconds(1), id);
        reset(tasks); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.RUNNING, 1, 0, null, null, null));
        coordinator.reconcile(); verify(tasks).preparation(id); verify(tasks, never()).prepare(any());
    }
    @Test void successfulRetryDoesNotConsumeAStaleDeadView() {
        long id = publish(); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.DEAD, 4, 3, null, null, "TASK_EXECUTION_FAILED"));
        coordinator.reconcile(); assertTrue(service.retry(id)); when(tasks.retry(id)).thenReturn(true);
        coordinator.reconcile(); assertEquals(AudioProcessingStatus.PROCESSING, rows.selectById(id).getProcessingStatus());
        assertFalse(rows.selectById(id).getPreparationRetry());
        jdbc.update("UPDATE audio SET preparation_after=? WHERE id=?", LocalDateTime.now().minusSeconds(1), id);
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
        var mvc = MockMvcBuilders.standaloneSetup(new AudioController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/audios/publish").contentType("application/json").content("{\"requestId\":\"http\",\"objectKey\":\"uploads/audio/9/day/a.wav\",\"durationMs\":1}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data").isString());
        assertNull(jdbc.queryForObject("SELECT duration_ms FROM audio", Integer.class));
        long id = jdbc.queryForObject("SELECT id FROM audio", Long.class);
        mvc.perform(get("/audios/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/audios/mine/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.processingStatus").value("processing"))
                .andExpect(jsonPath("$.data.reviewStatus").value("pending"));
    }
    AudioAnalysisContracts.Request analysisRequest(long id, MediaTaskType type) {
        var a = rows.selectById(id);
        return new AudioAnalysisContracts.Request(id, a.getUploaderId(), a.getTitle(), a.getObjectKey(),
                a.getDurationMs(), type, type == MediaTaskType.AUDIO_ASR ? null : a.getTranscriptKey());
    }
    void due(long id) { jdbc.update("UPDATE audio SET preparation_after=? WHERE id=?", LocalDateTime.now().minusSeconds(1).withNano(0), id); }
    long preparedForAnalysis() {
        long id = publish(); when(tasks.preparation(id)).thenReturn(new View(id, MediaTaskStatus.SUCCESS, 1, 0, prepared(id), null, null));
        coordinator.reconcile(); due(id); return id;
    }
    void recognize(long id) {
        var request = analysisRequest(id, MediaTaskType.AUDIO_ASR);
        var result = new AudioAnalysisContracts.TranscriptResult(id, request.objectKey(), request.prefix() + "asr/1/result.json", 0);
        when(tasks.analysis(id, request.type())).thenReturn(new AudioAnalysisContracts.View(id, request.type(), MediaTaskStatus.SUCCESS, 1, JsonUtil.toJson(result), null));
        coordinator.reconcile(); due(id);
    }
    void screen(long id) {
        var request = analysisRequest(id, MediaTaskType.AUDIO_REVIEW);
        var result = new AudioAnalysisContracts.ScreenResult(id, request.objectKey(), request.transcriptKey(), 0, true, false, List.of());
        when(tasks.analysis(id, request.type())).thenReturn(new AudioAnalysisContracts.View(id, request.type(), MediaTaskStatus.SUCCESS, 1, JsonUtil.toJson(result), null));
        coordinator.reconcile();
    }
    @Test void emptyTranscriptStillRequiresExplicitHumanApprovalAndKeepsVisibility() {
        long id = preparedForAnalysis(); recognize(id); screen(id);
        var a = rows.selectById(id); assertEquals(AudioAnalysisStatus.MANUAL, a.getAnalysisStatus()); assertEquals(AudioReviewStatus.PENDING, a.getReviewStatus());
        assertEquals(1, reviewRecords.selectCount(null)); assertThrows(BizException.class, () -> service.detail(id));
        jdbc.update("UPDATE audio SET visibility='private' WHERE id=?", id);
        assertTrue(reviews.decide(id, "PASS", "完整试听与封面核对通过", 1L));
        assertEquals("private", rows.selectById(id).getVisibility()); assertEquals(AudioReviewStatus.APPROVED, rows.selectById(id).getReviewStatus());
        assertThrows(BizException.class, () -> service.detail(id)); assertEquals(2, reviewRecords.selectCount(null));
        assertThrows(BizException.class, () -> reviews.decide(id, "PASS", "重复", 1L));
    }
    @Test void rejectCannotBeRecoveredByLateMachineResultOrUserRetry() {
        long id = preparedForAnalysis(); recognize(id); screen(id);
        assertTrue(reviews.decide(id, "REJECT", "封面不通过", 1L));
        screen(id); assertEquals(AudioReviewStatus.REJECTED, rows.selectById(id).getReviewStatus()); assertFalse(service.retry(id));
        assertEquals(2, reviewRecords.selectCount(null));
    }

    @Test void concurrentApprovalAndRejectionProduceOnlyOneFinalDecision() throws Exception {
        long id = manualReviewable();
        long before = rows.selectById(id).getIndexVersion();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pass = CompletableFuture.supplyAsync(() -> decideTogether(id, "PASS", ready, start));
        var reject = CompletableFuture.supplyAsync(() -> decideTogether(id, "REJECT", ready, start));
        try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
        finally { start.countDown(); }
        boolean approved = pass.get(5, TimeUnit.SECONDS); boolean rejected = reject.get(5, TimeUnit.SECONDS);
        assertNotEquals(approved, rejected);
        assertEquals(approved ? AudioReviewStatus.APPROVED : AudioReviewStatus.REJECTED, rows.selectById(id).getReviewStatus());
        assertEquals(before + 1, rows.selectById(id).getIndexVersion());
        assertEquals(1, manualAuditCount(id));
    }

    private boolean decideTogether(long id, String decision, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Review start timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        try { return reviews.decide(id, decision, "人工核对", 1L); }
        catch (BizException conflict) { assertEquals(ErrorCode.STATE_CONFLICT, conflict.getErrorCode()); return false; }
    }

    @ParameterizedTest @ValueSource(strings = {"PASS", "REJECT", "BAN"})
    void reviewWaitingForRowLockChecksDeletionAfterAcquiringIt(String decision) throws Exception {
        long id = manualReviewable();
        long before = rows.selectById(id).getIndexVersion();
        var entered = new CountDownLatch(1); var waiting = new AtomicReference<CompletableFuture<ErrorCode>>();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM audio WHERE id=? FOR UPDATE", Long.class, id);
            waiting.set(CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                try { reviews.decide(id, decision, "人工核对", 1L); return null; }
                catch (BizException error) { return error.getErrorCode(); }
            }));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            assertThrows(TimeoutException.class, () -> waiting.get().get(200, TimeUnit.MILLISECONDS));
            jdbc.update("UPDATE audio SET is_delete=1,index_version=index_version+1 WHERE id=?", id);
        });
        assertEquals(ErrorCode.NOT_FOUND, waiting.get().get(5, TimeUnit.SECONDS));
        assertEquals(AudioReviewStatus.PENDING, rows.selectById(id).getReviewStatus());
        assertEquals(before + 1, rows.selectById(id).getIndexVersion()); assertEquals(0, manualAuditCount(id));
    }

    @Test void visibilityChangeWhileBanWaitsDoesNotInvalidateTheDecision() throws Exception {
        long id = publish(); approve(id);
        long before = rows.selectById(id).getIndexVersion();
        var entered = new CountDownLatch(1); var waiting = new AtomicReference<CompletableFuture<Boolean>>();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM audio WHERE id=? FOR UPDATE", Long.class, id);
            waiting.set(CompletableFuture.supplyAsync(() -> { entered.countDown(); return reviews.decide(id, "BAN", "治理封禁", 1L); }));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            assertThrows(TimeoutException.class, () -> waiting.get().get(200, TimeUnit.MILLISECONDS));
            service.setVisibility(id, "private");
        });
        assertTrue(waiting.get().get(5, TimeUnit.SECONDS));
        assertEquals("private", rows.selectById(id).getVisibility());
        assertEquals(AudioReviewStatus.BANNED, rows.selectById(id).getReviewStatus());
        assertEquals(before + 2, rows.selectById(id).getIndexVersion()); assertEquals(1, manualAuditCount(id));
        assertThrows(BizException.class, () -> reviews.decide(id, "BAN", "重复封禁", 1L));
        assertEquals(1, manualAuditCount(id));
    }

    private int manualAuditCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audio_review_record WHERE audio_id=? AND source='MANUAL'", Integer.class, id);
    }

    @ParameterizedTest @EnumSource(value = AudioAnalysisStatus.class, names = {"RECOGNIZING", "SCREENING"})
    void approvalAndRejectionStillRequireACompletedAnalysisPhase(AudioAnalysisStatus phase) {
        long id = manualReviewable();
        jdbc.update("UPDATE audio SET analysis_status=? WHERE id=?", phase.getCode(), id);
        long before = rows.selectById(id).getIndexVersion();
        for (String decision : List.of("PASS", "REJECT")) {
            var error = assertThrows(BizException.class, () -> reviews.decide(id, decision, "人工核对", 1L));
            assertEquals(ErrorCode.STATE_CONFLICT, error.getErrorCode());
        }
        assertEquals(AudioReviewStatus.PENDING, rows.selectById(id).getReviewStatus());
        assertEquals(before, rows.selectById(id).getIndexVersion()); assertEquals(0, manualAuditCount(id));
    }
    @Test void failedAnalysisIsAuditedAndCanBeRetriedWithoutRepeatingPreparation() {
        long id = preparedForAnalysis(); var type = MediaTaskType.AUDIO_ASR;
        when(tasks.analysis(id, type)).thenReturn(new AudioAnalysisContracts.View(id, type, MediaTaskStatus.DEAD, 4, null, "private-internal-message"));
        coordinator.reconcile(); assertEquals(AudioAnalysisStatus.FAILED, rows.selectById(id).getAnalysisStatus());
        assertEquals(AudioProcessingStatus.READY, rows.selectById(id).getProcessingStatus()); assertEquals(1, reviewRecords.selectCount(null));
        assertFalse(service.getMine(id).getErrorMessage().contains("private-internal")); assertTrue(service.retry(id));
        when(tasks.retryAnalysis(id, type)).thenReturn(true); coordinator.reconcile();
        assertEquals(AudioAnalysisStatus.RECOGNIZING, rows.selectById(id).getAnalysisStatus());
        verify(tasks, never()).retry(id);
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullAnalysisStatusCanFailOnceWithoutRepeatedAuditRecords(boolean clearSchedule) {
        long id = preparedForAnalysis();
        jdbc.update("UPDATE audio SET analysis_status=NULL WHERE id=?", id);
        if (clearSchedule) jdbc.update("UPDATE audio SET preparation_after=NULL WHERE id=?", id);
        var audio = rows.selectById(id);
        reviews.fail(audio, "音频处理异常，请重试");
        var failed = rows.selectById(id);
        assertEquals(AudioAnalysisStatus.FAILED, failed.getAnalysisStatus());
        assertNull(failed.getPreparationAfter()); assertNull(failed.getProcessingDeadline());
        assertEquals("音频处理异常，请重试", failed.getErrorMessage());
        reviews.fail(audio, "重复结果");
        assertEquals(1, reviewRecords.selectCount(null));
    }
    @Test void bannedOrDeletedAudioCannotAcceptLateAnalysis() {
        long id = preparedForAnalysis();
        assertTrue(reviews.decide(id, "BAN", "治理封禁", 1L));
        recognize(id); assertEquals(AudioReviewStatus.BANNED, rows.selectById(id).getReviewStatus()); assertNull(rows.selectById(id).getTranscriptKey());
        assertThrows(BizException.class, () -> service.detail(id)); assertFalse(service.retry(id));
    }
    @Test void staleEsCandidatesAreFilteredBeforeSigningAndMineUsesOwnerScope() {
        long id = publish(); approve(id); when(search.search(any())).thenReturn(new AudioDiscoveryContracts.Slice(List.of(id, 123L), true));
        assertEquals(1, service.library("雨", new PageQuery()).getList().size());
        service.setVisibility(id, "private"); clearInvocations(storage);
        assertTrue(service.library("雨", new PageQuery()).getList().isEmpty()); verifyNoInteractions(storage);
        assertEquals(1, service.searchMine("雨", new PageQuery()).getList().size());
        verify(search).search(argThat(q -> Long.valueOf(9).equals(q.uploaderId())));
        UserContext.setUser("8"); clearInvocations(storage); assertTrue(service.searchMine("雨", new PageQuery()).getList().isEmpty()); verifyNoInteractions(storage);
    }
    @Test void onlyCurrentIndexVersionCanBeAcknowledgedAndDeleteLeavesTombstone() {
        long id = publish(); var documents = new InternalAudioDocumentController(rows);
        var first = documents.document(id); service.delete(id); var deleted = documents.document(id);
        assertTrue(deleted.deleted()); assertTrue(deleted.version() > first.version());
        documents.acknowledge(new AudioDiscoveryContracts.Ack(id, first.version()));
        assertEquals(0, rows.selectById(id).getIndexedVersion());
        documents.acknowledge(new AudioDiscoveryContracts.Ack(id, deleted.version()));
        assertEquals(deleted.version(), rows.selectById(id).getIndexedVersion()); assertNull(rows.selectById(id).getIndexAfter());
    }
    @Test void databaseDecisionAndAuditRollBackTogetherOnAuditFailure() {
        long id = manualReviewable(); var audio = rows.selectById(id);
        var broken = mock(AudioReviewRecordMapper.class);
        when(broken.insert(any(AudioReviewRecord.class))).thenThrow(new IllegalStateException("db error"));
        var service = new AudioReviewService(rows, broken,
                new TransactionTemplate(new DataSourceTransactionManager(database)));
        assertThrows(IllegalStateException.class, () -> service.decide(id, "PASS", "通过", 1L));
        assertEquals(AudioReviewStatus.PENDING, rows.selectById(id).getReviewStatus()); assertEquals(audio.getIndexVersion(), rows.selectById(id).getIndexVersion());
    }

    @Test void dirtyIndexStateSurvivesMqFailureAndRecoversOnNextDueScan() {
        long id = publish(); var publisher = mock(EsSyncPublisher.class);
        var sync = new AudioIndexSyncService(rows, publisher, new SimpleMeterRegistry());
        doThrow(new IllegalStateException("mq unavailable")).when(publisher).syncAudioDoc(id); sync.deliver();
        assertEquals(0, rows.selectById(id).getIndexedVersion()); assertTrue(rows.selectById(id).getIndexAfter().isAfter(LocalDateTime.now()));
        reset(publisher); jdbc.update("UPDATE audio SET index_after=? WHERE id=?", LocalDateTime.now().minusSeconds(1), id);
        sync.deliver(); verify(publisher).syncAudioDoc(id); assertEquals(0, rows.selectById(id).getIndexedVersion());
    }
    @Test void adminReviewReceivesTranscriptCoverAndAuditButOwnerDoesNotReceiveMachineInternals() throws Exception {
        long id = preparedForAnalysis(); recognize(id); screen(id);
        var artifacts = mock(ContentArtifactStore.class);
        var transcript = new ContentArtifacts.Transcript(List.of());
        when(artifacts.read(anyString(), eq(ContentArtifacts.Transcript.class))).thenReturn(transcript);
        var admin = new InternalAdminAudioController(rows, reviewRecords, reviews, storage, artifacts, users);
        assertEquals(1, admin.waiting(new PageQuery()).getTotal()); var detail = admin.detail(id);
        assertEquals("manual", detail.audio().analysisStatus());
        var mvc = MockMvcBuilders.standaloneSetup(admin).build();
        mvc.perform(get("/internal/admin/audios/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.audio.analysisStatus").value("manual"))
                .andExpect(jsonPath("$.audio.version").doesNotExist());
        assertEquals(transcript, detail.transcript()); assertEquals("signed/avatars/9.jpg", detail.coverUrl());
        assertNotNull(detail.previewUrl()); assertEquals("SKIPPED", detail.records().get(0).decision());
        assertFalse(JsonUtil.toJson(service.getMine(id)).contains("modelSkipped"));
        assertThrows(BizException.class, () -> reviews.decide(id, "PASS", "", 1L));
        assertThrows(BizException.class, () -> reviews.decide(id, null, "理由", 1L));
    }
    @Test void lateSuccessCannotReviveDeletedAudio() {
        long id = preparedForAnalysis(); var old = rows.selectById(id); var request = analysisRequest(id, MediaTaskType.AUDIO_ASR);
        var result = new AudioAnalysisContracts.TranscriptResult(id, old.getObjectKey(), request.prefix() + "asr/1/out.json", 0);
        var view = new AudioAnalysisContracts.View(id, request.type(), MediaTaskStatus.SUCCESS, 1, JsonUtil.toJson(result), null);
        service.delete(id); reviews.accept(old, view, request); assertNull(rows.selectById(id).getTranscriptKey());
    }

    @Test void expiredAnalysisCannotAcceptAResultAndManualOverrideUsesCurrentState() throws Exception {
        long id = preparedForAnalysis(); var request = analysisRequest(id, MediaTaskType.AUDIO_ASR);
        jdbc.update("UPDATE audio SET processing_deadline=? WHERE id=?", LocalDateTime.now().minusSeconds(1), id);
        var old = rows.selectById(id);
        var result = new AudioAnalysisContracts.TranscriptResult(id, old.getObjectKey(), request.prefix() + "asr/1/out.json", 0);
        reviews.accept(old, new AudioAnalysisContracts.View(id, request.type(), MediaTaskStatus.SUCCESS, 1, JsonUtil.toJson(result), null), request);
        assertNull(rows.selectById(id).getTranscriptKey()); coordinator.reconcile();
        assertEquals(AudioAnalysisStatus.FAILED, rows.selectById(id).getAnalysisStatus());
        assertTrue(rows.selectById(id).getIndexVersion() > old.getIndexVersion());
        assertTrue(reviews.decide(id, "PASS", "技术识别失败，人工完整试听及封面核验通过", 1L));
        assertEquals(AudioReviewStatus.APPROVED, rows.selectById(id).getReviewStatus());
        assertEquals("您的音频已通过审核。", service.getMine(id).getReviewReason());
        assertEquals("技术识别失败，人工完整试听及封面核验通过", rows.selectById(id).getReviewReason());
        when(search.search(any())).thenReturn(new AudioDiscoveryContracts.Slice(List.of(id), false));
        var mvc = MockMvcBuilders.standaloneSetup(new AudioController(service)).build();
        mvc.perform(get("/audios/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewReason").doesNotExist())
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.data.analysisStatus").doesNotExist());
        mvc.perform(get("/audios/library")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].reviewReason").doesNotExist());
        mvc.perform(get("/audios/mine/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewReason").value("您的音频已通过审核。"));
    }

    @ParameterizedTest
    @EnumSource(AudioAnalysisStatus.class)
    void analysisStatusUsesLowercaseInDatabaseConditionsAndOwnerJson(AudioAnalysisStatus phase) throws Exception {
        long id = publish(); rows.updateById(new Audio().setId(id).setAnalysisStatus(phase));
        assertEquals(phase.getCode(), jdbc.queryForObject("SELECT analysis_status FROM audio WHERE id=?", String.class, id));
        var matching = rows.selectOne(new LambdaQueryWrapper<Audio>()
                .eq(Audio::getId, id).eq(Audio::getAnalysisStatus, phase));
        assertEquals(phase, matching.getAnalysisStatus());
        var mvc = MockMvcBuilders.standaloneSetup(new AudioController(service)).build();
        mvc.perform(get("/audios/mine/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value(phase.getCode()));
    }

    @Test void indexWaitingAgeSurvivesRetriesAndMutationsUntilCurrentVersionIsAcknowledged() {
        long id = publish(); approve(id);
        var documents = new InternalAudioDocumentController(rows);
        documents.acknowledge(new AudioDiscoveryContracts.Ack(id, rows.selectById(id).getIndexVersion()));
        assertNull(rows.selectById(id).getIndexPendingSince());
        service.setVisibility(id, "private"); assertNotNull(rows.selectById(id).getIndexPendingSince());
        var waiting = LocalDateTime.now().minusMinutes(10).withNano(0);
        jdbc.update("UPDATE audio SET index_pending_since=?,index_after=? WHERE id=?", waiting, waiting, id);
        long previousVersion = rows.selectById(id).getIndexVersion();
        var publisher = mock(EsSyncPublisher.class);
        doThrow(new IllegalArgumentException("one bad record")).when(publisher).syncAudioDoc(id);
        new AudioIndexSyncService(rows, publisher, new SimpleMeterRegistry()).deliver();
        assertEquals(waiting, rows.selectById(id).getIndexPendingSince());
        service.setVisibility(id, "public"); assertEquals(waiting, rows.selectById(id).getIndexPendingSince());
        documents.acknowledge(new AudioDiscoveryContracts.Ack(id, previousVersion));
        assertEquals(waiting, rows.selectById(id).getIndexPendingSince());
        documents.acknowledge(new AudioDiscoveryContracts.Ack(id, rows.selectById(id).getIndexVersion()));
        assertNull(rows.selectById(id).getIndexPendingSince());
        service.delete(id); assertNotNull(rows.selectById(id).getIndexPendingSince());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT", "BAN"})
    void audioOwnerFeedbackIsFixedAndAdministratorKeepsOriginalRemarks(String decision) {
        long id = preparedForAnalysis(); recognize(id); screen(id);
        String internal = "内部判断及核对细节，不直接通知作者";
        reviews.decide(id, decision, internal, 1L);
        assertEquals("您的音频未通过审核，已限制展示。", service.getMine(id).getReviewReason());
        assertEquals("您的音频未通过审核，已限制展示。", service.myAudios(new PageQuery()).getList().get(0).getReviewReason());
        assertEquals(internal, rows.selectById(id).getReviewReason());
        assertEquals(internal, reviewRecords.selectList(null).stream().filter(r -> "MANUAL".equals(r.getSource())).findFirst().orElseThrow().getReason());
        assertThrows(BizException.class, () -> service.detail(id));
    }

}
