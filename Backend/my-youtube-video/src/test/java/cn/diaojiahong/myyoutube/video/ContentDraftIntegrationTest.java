package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
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
        @Bean S3StorageUtil storage() { return mock(S3StorageUtil.class); }
        @Bean IAudioService audio() { return mock(IAudioService.class); }
        @Bean MediaTaskPort tasks() { return mock(MediaTaskPort.class); }
        @Bean ContentUnderstandingService understanding() { return mock(ContentUnderstandingService.class); }
        @Bean DynamicMediaPreparationService preparation(S3StorageUtil storage, IAudioService audio) {
            return new DynamicMediaPreparationService(new SyncChainStepRegistry(List.of(
                    new ItemsCountStep(), new CanvasStyleStep(), new AudioReferenceStep(audio, storage), new ItemsPrepareStep(storage))));
        }
        @Bean ContentDraftService drafts(MediaDraftMapper rows, DynamicMediaPreparationService preparation) {
            return new ContentDraftService(rows, preparation);
        }
        @Bean MediaDraftService mediaDrafts(MediaDraftMapper rows) { return new MediaDraftService(rows); }
        @Bean ContentSubmissionService publish(MediaDraftService drafts, MediaTaskPort tasks, VideoMapper videos,
                                            DynamicMapper dynamics, DynamicMediaPreparationService preparation,
                                            ContentMapper contents, ContentUnderstandingService understanding, TransactionTemplate tx) {
            var strategies = new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos, tasks), new DynamicContentStrategy(dynamics, preparation)));
            return new ContentSubmissionService(drafts, contents, strategies, understanding, tx);
        }
    }

    @Autowired DataSource dataSource;
    @Autowired ContentDraftService drafts;
    @Autowired MediaDraftService mediaDrafts;
    @Autowired MediaDraftMapper rows;
    @Autowired ContentSubmissionService publish;
    @Autowired MediaTaskPort tasks;
    @Autowired ContentUnderstandingService understanding;
    @Autowired S3StorageUtil storage;
    @Autowired IAudioService audio;
    JdbcTemplate jdbc;
    final String first = "uploads/picture/9/20261011/a.png", second = "uploads/picture/9/20261011/b.png";

    @BeforeEach void schema() {
        reset(tasks, understanding, storage, audio);
        UserContext.setUser("9");
        jdbc = new JdbcTemplate(dataSource);
        ContentTestDatabase.drop(jdbc,"media_draft","video","dynamic","content");
        jdbc.execute("""
                CREATE TABLE media_draft(id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,type VARCHAR(16) NOT NULL,
                  title VARCHAR(100),description VARCHAR(512),visibility VARCHAR(16) DEFAULT 'public',allow_comment INT DEFAULT 1,
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

    @Test void nativeDraftIsReadyWithoutTranscodeAndSupportsSelectedCover() {
        var dto = dynamic(); dto.setCoverImageObjectKey(second); dto.setAllowComment(0);
        var result = drafts.saveDynamic(null, 9L, dto);
        assertEquals("ready", result.status()); assertEquals("dynamic", result.type());
        assertEquals("标题", result.title()); assertEquals("简介", result.description());
        assertEquals(second, result.coverObjectKey());
        assertEquals(List.of(first, second), result.dynamic().items().stream().map(i -> i.imageObjectKey()).toList());
        assertEquals(5200, result.dynamic().exportDurationMs()); assertNull(result.dynamic().audio());
        assertEquals("public", result.visibility()); assertEquals(0, result.allowComment());
        assertEquals(result, drafts.get(result.draftId(), 9L));
        assertNull(result.objectKey());
        verifyNoInteractions(tasks, understanding, audio);
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
        when(storage.readHeadBytes("uploads/audio/7/20261011/music.mp3", 1)).thenThrow(new BizException(BizException.STORAGE_ERROR, "对象不存在"));
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft", Integer.class));
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
        when(storage.readHeadBytes(second, 16)).thenReturn(new byte[16]);
        assertThrows(BizException.class, () -> drafts.saveDynamic(null, 9L, dto));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media_draft", Integer.class));
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

    Long readyVideo() {
        Long id = mediaDrafts.createDraft(9L, "uploads/video/9/20261011/original.mp4", "original.mp4");
        assertTrue(mediaDrafts.transition(id, MediaDraftStatusEnum.UPLOADING, MediaDraftStatusEnum.PROCESSING, null, null));
        assertTrue(mediaDrafts.transition(id, MediaDraftStatusEnum.PROCESSING, MediaDraftStatusEnum.READY, 1501L, null));
        when(tasks.getTaskResults(id)).thenReturn(List.of(new MediaTaskPort.TaskResult("TRANSCODE", "SUCCESS", "{\"processedObjectKey\":\"processed.mp4\",\"durationMs\":1501}")));
        return id;
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
        var firstReceipt = publish.submit(draft.draftId(), 9L);
        assertEquals(firstReceipt, publish.submit(draft.draftId(), 9L));
        assertEquals("submitted", firstReceipt.draftStatus());
        assertEquals("review", jdbc.queryForObject("SELECT status FROM content", String.class));
        assertEquals(second, jdbc.queryForObject("SELECT cover_object_key FROM content", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT allow_comment FROM content", Integer.class));
        assertEquals(rows.selectById(draft.draftId()).getMediaJson(), jdbc.queryForObject("SELECT media_json FROM dynamic", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM video", Integer.class));
        verify(understanding, times(1)).enroll(any(), argThat(m -> m.dynamic() != null));
        verifyNoInteractions(tasks);
    }
    @Test void editDuringMaterialValidationCannotSubmitOldSnapshot() {
        Long id = readyVideo();
        var metadata = new ContentMetadataDTO(); metadata.setTitle("初始"); drafts.saveMetadata(id, 9L, metadata);
        var output = tasks.getTaskResults(id);
        when(tasks.getTaskResults(id)).thenAnswer(call -> {
            metadata.setTitle("更新"); drafts.saveMetadata(id, 9L, metadata); return output;
        });
        assertThrows(BizException.class, () -> publish.submit(id, 9L));
        assertEquals("更新", rows.selectById(id).getTitle());
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(id).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
        verify(understanding, never()).enroll(any(), any());
    }
    @Test void nativeSubmissionRechecksAudioAndStoredImages() {
        var dto = dynamic(); dto.setAudioId(20L);
        when(audio.getReferenceable(20L, 9L)).thenReturn(new Audio().setId(20L).setObjectKey("uploads/audio/7/20261011/music.mp3").setDurationMs(2000));
        var draft = drafts.saveDynamic(null, 9L, dto);
        when(audio.getReferenceable(20L, 9L)).thenThrow(new BizException(BizException.BIZ_ERROR,"已移除音频权限"));
        assertThrows(BizException.class, () -> publish.submit(draft.draftId(), 9L));
        assertEquals(MediaDraftStatusEnum.READY, rows.selectById(draft.draftId()).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content", Integer.class));
    }
}
