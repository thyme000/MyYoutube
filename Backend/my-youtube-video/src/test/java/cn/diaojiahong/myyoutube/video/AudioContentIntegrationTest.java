package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.service.impl.ContentFeedServiceImpl;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.strategy.DynamicContentStrategy;
import cn.diaojiahong.myyoutube.content.strategy.VideoContentStrategy;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.search.api.AudioSearchPort;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import cn.diaojiahong.myyoutube.video.controller.AudioController;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioContentQuery;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioContentCursor;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioProcessingStatus;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentReviewRecordMapper;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.AudioContentService;
import cn.diaojiahong.myyoutube.video.service.AudioUploadService;
import cn.diaojiahong.myyoutube.video.service.impl.AudioServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AudioContentIntegrationTest.Config.class)
class AudioContentIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = {ContentMapper.class, AudioMapper.class, ContentLikeMapper.class})
    static class Config {
        @Bean DataSource dataSource() { return ContentTestDatabase.create("audio-contents"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            factory.setMapperLocations(new ClassPathResource("mapper/ContentMapper.xml")); return factory.getObject();
        }
        @Bean IContentService contents() { return new ContentServiceImpl(); }
    }
    @Autowired DataSource database;
    @Autowired AudioMapper audios;
    @Autowired DynamicMapper dynamics;
    @Autowired VideoMapper videos;
    @Autowired ContentMapper contents;
    @Autowired IContentService contentService;
    @Autowired ContentLikeMapper likes;
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final UserPort users = mock(UserPort.class);
    final AudioContentAccess access = mock(AudioContentAccess.class);
    JdbcTemplate jdbc;
    AudioServiceImpl audioService;
    AudioContentService service;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(database);
        ContentTestDatabase.drop(jdbc, "audio", "content", "dynamic", "video", "content_like");
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        for (String table : List.of("audio", "content", "dynamic", "video", "content_like")) {
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`");
            String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
            // H2 会把字符串写成 JSON 字符串值；原生 MySQL 验证保留真实 JSON 列。
            if (!Boolean.getBoolean("content.integration")) ddl = ddl.replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replace(" ENGINE=InnoDB", "").replace(" JSON NOT NULL", " TEXT NOT NULL");
            jdbc.execute(ddl);
        }
        reset(storage, users, access);
        when(storage.signGetUrl(anyString(), anyLong())).thenAnswer(call -> "signed/" + call.getArgument(0));
        audioService = spy(new AudioServiceImpl(audios, storage, users, new ContentSubmissionRules("content-moderation/blocked.txt"),
                new AudioProcessingProperties(), mock(AudioSearchPort.class)));
        var strategies = new ContentTypeStrategyRegistry(List.of(new DynamicContentStrategy(dynamics, audioService), new VideoContentStrategy(videos)));
        var cards = new ContentFeedServiceImpl(contentService, likes, mock(IUserFollowService.class), users, storage,
                mock(RecommendPort.class), strategies, access, mock(ContentReviewRecordMapper.class));
        service = new AudioContentService(audioService, contents, cards);
        UserContext.setUser("9");
        for (long id : List.of(20L, 30L)) audios.insert(new Audio().setId(id).setUploaderId(9L).setTitle("音频" + id)
                .setRequestId("request-" + id).setRequestFingerprint("a".repeat(64)).setSourceObjectKey("uploads/audio/9/" + id)
                .setUploadSize(100L).setUploadContentType("audio/wav").setUploadDeadline(LocalDateTime.now().plusHours(1))
                .setProcessingStatus(AudioProcessingStatus.READY).setReviewStatus(AudioReviewStatus.APPROVED)
                .setVisibility(ContentVisibilityEnum.PUBLIC.getCode()).setIsDelete(0));
        for (long id = 1; id <= 11; id++) {
            long count = id == 4 ? 100 : id == 2 || id == 3 ? 8 : 5;
            LocalDateTime time = LocalDateTime.of(2026, 10, 26, 9, id == 2 || id == 3 ? 1 : 0);
            if (id == 4) time = time.minusHours(1);
            Long audioId = id == 11 || id == 9 ? null : id == 10 ? 30L : 20L;
            contents.insert(new Content().setId(id).setType(id == 9 ? ContentTypeEnum.VIDEO : ContentTypeEnum.DYNAMIC)
                    .setAudioId(audioId)
                    .setAuthorId(9L).setTitle("作品" + id).setCoverObjectKey("cover/" + id).setAllowComment(1)
                    .setStatus(id == 5 ? ContentStatusEnum.REVIEW : id == 6 ? ContentStatusEnum.BANNED : ContentStatusEnum.PUBLISHED)
                    .setVisibility((id == 7 ? ContentVisibilityEnum.PRIVATE : ContentVisibilityEnum.PUBLIC).getCode())
                    .setIsDelete(id == 8 ? 1 : 0).setLikeCount(count).setCreateTime(time));
            var media = new DynamicMedia("vertical", 1080, 1920, "cut", 0,
                    List.of(new DynamicMedia.Image("image/" + id, 0, 3000, .5, .5)),
                    audioId == null ? null : new DynamicMedia.Audio(audioId, "audio/" + audioId, "音频", 3000), 3000);
            if (id == 9) videos.insert(new Video().setId(id).setObjectKey("video/9.mp4").setDurationMs(3000L));
            else dynamics.insert(new Dynamic().setId(id).setMediaJson(JsonUtil.toJson(media)));
        }
    }
    @AfterEach void cleanup() { UserContext.removeUser(); }
    AudioContentQuery query(String sort, String cursor, int size) {
        var query = new AudioContentQuery(); query.setSort(sort); query.setCursor(cursor); query.setPageSize(size); return query;
    }

    @Test void latestPaginationIsStableAndOmitsNonPublicOrUnrelatedContents() {
        CursorPage<ContentFeedVO, String> first = service.list(20L, 9L, query(null, null, 2));
        assertEquals(List.of("3", "2"), first.items().stream().map(c -> c.getId()).toList()); assertNotNull(first.nextCursor());
        CursorPage<ContentFeedVO, String> second = service.list(20L, 9L, query("latest", first.nextCursor(), 2));
        assertEquals(List.of("1", "4"), second.items().stream().map(c -> c.getId()).toList()); assertNull(second.nextCursor());
        CursorPage<ContentFeedVO, String> exact = service.list(20L, null, query("latest", null, 4));
        assertEquals(4, exact.items().size()); assertNull(exact.nextCursor());
        String older = new AudioContentCursor(20, ContentSortEnum.LATEST, 0, LocalDateTime.of(2020, 1, 1, 0, 0), 1).encode();
        CursorPage<ContentFeedVO, String> empty = service.list(20L, null, query("latest", older, 2));
        assertTrue(empty.items().isEmpty()); assertNull(empty.nextCursor());
        assertEquals("signed/cover/3", first.items().get(0).getCoverUrl());
        assertEquals("published", first.items().get(0).getStatus());
        assertEquals("dynamic", first.items().get(0).getType());
        assertEquals(3000L, first.items().get(0).getExportDurationMs());
        verifyNoInteractions(access);
    }

    @Test void mostLikedUsesCreationTimeAndIdToBreakTies() {
        CursorPage<ContentFeedVO, String> first = service.list(20L, null, query("mostLiked", null, 2));
        assertEquals(List.of("4", "3"), first.items().stream().map(c -> c.getId()).toList()); assertNotNull(first.nextCursor());
        CursorPage<ContentFeedVO, String> second = service.list(20L, null, query("mostLiked", first.nextCursor(), 2));
        assertEquals(List.of("2", "1"), second.items().stream().map(c -> c.getId()).toList()); assertNull(second.nextCursor());
    }

    @Test void associationListUsesTheCommonReferenceAndExistingMediaStrategies() {
        jdbc.update("UPDATE content SET audio_id=20 WHERE id=9");
        CursorPage<ContentFeedVO, String> page = service.list(20L, null, query(null, null, 10));
        assertEquals(List.of("3", "2", "9", "1", "4"), page.items().stream().map(c -> c.getId()).toList());
        ContentFeedVO video = page.items().stream().filter(c -> "9".equals(c.getId())).findFirst().orElseThrow();
        assertEquals("video", video.getType());
        assertEquals("signed/video/9.mp4", video.getVideoUrl());
        assertEquals(3000L, video.getDurationMs());
    }

    @Test void newlyPublishedContentBeforeTheCursorDoesNotRepeatThePreviousPage() {
        CursorPage<ContentFeedVO, String> first = service.list(20L, null, query(null, null, 2));
        Content template = contents.selectById(1L);
        template.setId(100L).setCreateTime(template.getCreateTime().plusDays(1));
        contents.insert(template);
        dynamics.insert(new Dynamic().setId(100L).setMediaJson(dynamics.selectById(1L).getMediaJson()));
        CursorPage<ContentFeedVO, String> second = service.list(20L, null, query(null, first.nextCursor(), 2));
        assertEquals(List.of("1", "4"), second.items().stream().map(c -> c.getId()).toList());
        assertNull(second.nextCursor());
    }

    @Test void deletingTheCursorBoundaryDoesNotSkipRemainingContents() {
        CursorPage<ContentFeedVO, String> first = service.list(20L, null, query(null, null, 2));
        jdbc.update("UPDATE content SET is_delete=1 WHERE id=2");
        CursorPage<ContentFeedVO, String> second = service.list(20L, null, query(null, first.nextCursor(), 2));
        assertEquals(List.of("1", "4"), second.items().stream().map(c -> c.getId()).toList());
        assertNull(second.nextCursor());
    }

    @ParameterizedTest @ValueSource(strings = {"private", "deleted", "banned", "pending", "processing", "missing"})
    void unavailableAudioRejectsEvenItsOwnerBeforeCardAssembly(String condition) {
        switch (condition) {
            case "private" -> jdbc.update("UPDATE audio SET visibility='private' WHERE id=20");
            case "deleted" -> jdbc.update("UPDATE audio SET is_delete=1 WHERE id=20");
            case "banned" -> jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
            case "pending" -> jdbc.update("UPDATE audio SET review_status='pending' WHERE id=20");
            case "processing" -> jdbc.update("UPDATE audio SET processing_status='processing' WHERE id=20");
            case "missing" -> jdbc.update("DELETE FROM audio WHERE id=20");
        }
        assertThrows(BizException.class, () -> service.list(20L, 9L, query(null, null, 10)));
        verifyNoInteractions(storage, users);
    }

    @ParameterizedTest @ValueSource(strings = {"private", "banned"})
    void queryRechecksAudioVisibilityAfterTheInitialAccessCheck(String change) {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            jdbc.update(change.equals("private") ? "UPDATE audio SET visibility='private' WHERE id=20" : "UPDATE audio SET review_status='banned' WHERE id=20");
            return result;
        }).when(audioService).getPublic(20L);
        CursorPage<ContentFeedVO, String> page = service.list(20L, 9L, query(null, null, 10));
        assertTrue(page.items().isEmpty()); assertNull(page.nextCursor());
        verifyNoInteractions(storage, users);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 101})
    void invalidPageSizeRejectsBeforeAudioOrContentQueries(int size) {
        assertThrows(BizException.class, () -> service.list(20L, null, query(null, null, size)));
        verify(audioService, never()).getPublic(anyLong()); verifyNoInteractions(storage, users);
    }

    @ParameterizedTest @ValueSource(strings = {"hot", "recommended", "mostPlayed", "unknown"})
    void unsupportedSortRejectsBeforeAudioOrContentQueries(String sort) {
        assertThrows(BizException.class, () -> service.list(20L, null, query(sort, null, 10)));
        verify(audioService, never()).getPublic(anyLong()); verifyNoInteractions(storage, users);
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "other-audio", "other-sort", "missing-time", "negative-count", "bad-id", "oversize"})
    void invalidOrMismatchedCursorRejectsBeforeDatabaseAndCardQueries(String problem) {
        var position = new AudioContentCursor(20, ContentSortEnum.LATEST, 8, LocalDateTime.of(2026, 10, 26, 9, 1), 2);
        String cursor = switch (problem) {
            case "malformed" -> "not-a-cursor";
            case "other-audio" -> new AudioContentCursor(30, position.sort(), position.likeCount(), position.createTime(), position.contentId()).encode();
            case "other-sort" -> new AudioContentCursor(20, ContentSortEnum.MOST_LIKED, position.likeCount(), position.createTime(), position.contentId()).encode();
            case "missing-time" -> new AudioContentCursor(20, position.sort(), position.likeCount(), null, position.contentId()).encode();
            case "negative-count" -> new AudioContentCursor(20, position.sort(), -1, position.createTime(), position.contentId()).encode();
            case "bad-id" -> new AudioContentCursor(20, position.sort(), position.likeCount(), position.createTime(), 0).encode();
            case "oversize" -> "X".repeat(513);
            default -> throw new AssertionError(problem);
        };
        assertThrows(BizException.class, () -> service.list(20L, null, query(null, cursor, 10)));
        verify(audioService, never()).getPublic(anyLong()); verifyNoInteractions(storage, users);
    }

    @Test void endpointReturnsOnlyListAndLookaheadWithoutCountsAndRejectsBadParameters() throws Exception {
        UserContext.removeUser();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AudioController(audioService, mock(AudioUploadService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/audios/20/contents").param("sort", "latest").param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("3"))
                .andExpect(jsonPath("$.data.hasMore").doesNotExist()).andExpect(jsonPath("$.data.nextCursor").isString())
                .andExpect(jsonPath("$.data.list").doesNotExist())
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.pages").doesNotExist()).andExpect(jsonPath("$.data.usageCount").doesNotExist());
        mvc.perform(get("/audios/20/contents").param("sort", "unknown")).andExpect(status().isBadRequest());
        mvc.perform(get("/audios/20/contents").param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/audios/20/contents").param("cursor", "invalid")).andExpect(status().isBadRequest());
        String cursor = service.list(20L, null, query(null, null, 2)).nextCursor();
        mvc.perform(get("/audios/20/contents").param("cursor", cursor).param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("1"))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
        mvc.perform(get("/audios/999/contents")).andExpect(status().isNotFound());
    }
}
