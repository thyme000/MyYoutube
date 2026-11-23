package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import cn.diaojiahong.myyoutube.common.media.VideoMedia;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportDTO;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportService;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.content.service.ContentPlaybackService;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.strategy.DynamicContentStrategy;
import cn.diaojiahong.myyoutube.content.strategy.VideoContentStrategy;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.mapper.UserContentBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.content.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 统一播放上报的 MySQL 落库规则测试: 历史按实际展示记、PLAY/SKIP/FINISH 判定、
 * 播放量跟随 PLAY 行首插(会话期即窗口, 同会话同内容至多一次)、
 * 删除为物理删(历史与续播位置同行同生命周期, 删除后在途/重投的旧观看会重建, 已接受的取舍)、
 * 续播按服务端版本(盖章接收时间)接受。
 */
class PlaybackReportServiceTest {

    static final long USER = 9L;

    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    SqlSessionFactory factory;
    SqlSession session;
    IUserTagPreferenceService tagPreferences;
    ContentTypeStrategyRegistry strategies;
    PlaybackReportService service;

    @BeforeEach
    void setup() throws Exception {
        ds = ContentTestDatabase.create("playback-report");
        jdbc = new JdbcTemplate(ds);
        ContentTestDatabase.drop(jdbc, "content", "user_view_history", "user_content_behavior");
        for (String table : List.of("content", "user_view_history", "user_content_behavior")) {
            createTableFromSchema(table);
        }
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(
                new ClassPathResource("mapper/UserViewHistoryMapper.xml"),
                new ClassPathResource("mapper/UserContentBehaviorMapper.xml"));
        factory = ContentTestDatabase.sharedReads(bean.getObject());
        // ContentMapper 无 XML, BaseMapper 方法需显式注册
        ((com.baomidou.mybatisplus.core.MybatisConfiguration) factory.getConfiguration())
                .addMapper(ContentMapper.class);
        session = factory.openSession(true);
        tagPreferences = mock(IUserTagPreferenceService.class);
        strategies = mock(ContentTypeStrategyRegistry.class);
        service = newService(factory.openSession(true));
    }

    @AfterEach
    void close() {
        session.close();
    }

    /** 与 ContentLikeRecommendationIntegrationTest 同款: 直接取 schema.sql 建表, H2 下需避免全局重名索引 */
    private void createTableFromSchema(String table) throws Exception {
        String schema = java.nio.file.Files.readString(java.nio.file.Path.of("../../resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`");
        String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
        ddl = ddl.replace(" ENGINE=InnoDB", "")
                .replace("KEY `idx_content_id`", "KEY `idx_" + table + "_content_id`");
        jdbc.execute(ddl);
    }

    private PlaybackReportService newService(SqlSession sqlSession) {
        return new PlaybackReportService(
                sqlSession.getMapper(UserViewHistoryMapper.class),
                sqlSession.getMapper(UserContentBehaviorMapper.class),
                sqlSession.getMapper(ContentMapper.class),
                tagPreferences,
                strategies);
    }

    private void insertContent(long id, ContentTypeEnum type, String status, int isDelete) {
        jdbc.update("INSERT INTO content (id, type, author_id, title, status, is_delete) VALUES (?, ?, 1, 't', ?, ?)",
                id, type.getCode(), status, isDelete);
    }

    private void stubVideoDuration(long contentId, long durationMs) {
        VideoContentStrategy strategy = spy(new VideoContentStrategy(mock(VideoMapper.class)));
        ContentMedia media = new ContentMedia("video", new VideoMedia("v", durationMs), null);
        doReturn(Map.of(contentId, media)).when(strategy).loadMedia(any());
        when(strategies.get(ContentTypeEnum.VIDEO)).thenReturn(strategy);
    }

    private void stubDynamicDuration(long contentId, long exportDurationMs) {
        DynamicContentStrategy strategy = spy(new DynamicContentStrategy(mock(DynamicMapper.class),
                mock(IAudioService.class)));
        DynamicMedia dynamic = new DynamicMedia("vertical", 1080, 1920, "fade", 800,
                List.of(new DynamicMedia.Image("a", 0, 3000, .5, .5),
                        new DynamicMedia.Image("b", 2200, exportDurationMs - 2200, .5, .5)),
                null, exportDurationMs);
        ContentMedia media = new ContentMedia("dynamic", null, dynamic);
        doReturn(Map.of(contentId, media)).when(strategy).loadMedia(any());
        when(strategies.get(ContentTypeEnum.DYNAMIC)).thenReturn(strategy);
    }

    private PlaybackReportDTO report(String sessionId, long contentId,
                                     long effectiveMs, long foregroundMs, Long positionMs) {
        PlaybackReportDTO dto = new PlaybackReportDTO();
        dto.setContentId(contentId);
        dto.setSessionId(sessionId);
        dto.setReceivedAt(System.currentTimeMillis());
        dto.setEffectiveMs(effectiveMs);
        dto.setForegroundMs(foregroundMs);
        dto.setPositionMs(positionMs);
        return dto;
    }

    private LocalDateTime ts(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private Long playCount(long contentId) {
        return jdbc.queryForObject("SELECT play_count FROM content WHERE id = " + contentId, Long.class);
    }

    private Long resumePosition(long contentId) {
        return jdbc.queryForObject(
                "SELECT position_ms FROM user_view_history WHERE user_id = " + USER + " AND content_id = " + contentId,
                Long.class);
    }

    @Test
    void leavingWithinASecondRecordsHistoryAndSkip() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);
        PlaybackReportDTO report = report("s1", 1, 600, 600, 0L);
        long receivedAt = System.currentTimeMillis();
        report.setReceivedAt(receivedAt);

        service.apply(USER, List.of(report));

        // 展示过就记历史, 不等 3 秒也不等 PLAY 门槛
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));
        assertEquals(ts(receivedAt),
                jdbc.queryForObject("SELECT view_time FROM user_view_history", LocalDateTime.class));
        assertEquals("skip", jdbc.queryForObject("SELECT event_type FROM user_content_behavior", String.class));
        assertEquals("s1", jdbc.queryForObject("SELECT session_id FROM user_content_behavior", String.class));
        assertEquals(600L, jdbc.queryForObject("SELECT duration_ms FROM user_content_behavior", Long.class));
        verify(tagPreferences).addBehaviorScore(USER, 1L, EventEnum.SKIP);
        verifyNoMoreInteractions(tagPreferences);
        assertEquals(0L, playCount(1));
    }

    @Test
    void videoFinishRequiresBothEffectivePlaybackAndPositionAt95Percent() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);

        service.apply(USER, List.of(
                report("s1", 1, 1_000, 1_000, 9_500L),
                report("s1", 1, 9_499, 9_499, 9_500L),
                report("s1", 1, 9_500, 9_500, 9_499L),
                report("s1", 1, 9_500, 9_500, 0L)));

        assertEquals(List.of("play"), jdbc.queryForList("SELECT event_type FROM user_content_behavior", String.class));

        PlaybackReportDTO finish = report("s1", 1, 9_500, 9_500, 9_500L);
        service.apply(USER, List.of(finish));
        service.apply(USER, List.of(finish));

        assertEquals(List.of("finish", "play"),
                jdbc.queryForList("SELECT event_type FROM user_content_behavior ORDER BY event_type", String.class));
        assertEquals(1L, playCount(1));
        verify(tagPreferences).addBehaviorScore(USER, 1L, EventEnum.PLAY);
        verify(tagPreferences).addBehaviorScore(USER, 1L, EventEnum.FINISH);
        verifyNoMoreInteractions(tagPreferences);
    }

    @Test
    void dynamicReachingPlayThresholdRegistersOnlyPlay() {
        insertContent(2, ContentTypeEnum.DYNAMIC, "published", 0);
        stubDynamicDuration(2, 10_000);

        service.apply(USER, List.of(report("s1", 2, 7_000, 7_000, 0L)));

        assertEquals("play", jdbc.queryForObject("SELECT event_type FROM user_content_behavior", String.class));
        verify(tagPreferences).addBehaviorScore(USER, 2L, EventEnum.PLAY);
        assertEquals(0L, resumePosition(2));
        // 会话内首次达标即计入播放量
        assertEquals(1L, playCount(2));

        // 差 1 毫秒未达 PLAY，后续达双 95% 的动态也只登记 PLAY。
        service.apply(USER, List.of(report("s2", 2, 6_999, 6_999, 0L)));
        service.apply(USER, List.of(report("s3", 2, 9_500, 9_500, 9_500L)));
        assertEquals(List.of("s1", "s3"), jdbc.queryForList(
                "SELECT session_id FROM user_content_behavior ORDER BY session_id", String.class));
        verify(tagPreferences, times(2)).addBehaviorScore(USER, 2L, EventEnum.PLAY);
        verifyNoMoreInteractions(tagPreferences);
    }

    @Test
    void dynamicResumeUsesImageTimelineAndReturnsTheSavedPosition() {
        insertContent(2, ContentTypeEnum.DYNAMIC, "published", 0);
        stubDynamicDuration(2, 10_000);
        ContentPlaybackService playback = new ContentPlaybackService(mock(ContentAccessService.class),
                session.getMapper(UserViewHistoryMapper.class));
        service.apply(USER, List.of(report("s1", 2, 0, 0, 10_500L)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));
        assertEquals(0L, playback.get(2L, USER).positionMs());

        long initialTime = System.currentTimeMillis();
        jdbc.update("INSERT INTO user_view_history (user_id, content_id, view_time) VALUES (?, ?, ?)",
                USER, 2L, ts(initialTime));
        session.clearCache();
        assertEquals(0L, playback.get(2L, USER).positionMs());
        assertNull(jdbc.queryForObject("SELECT reported_at FROM user_view_history", Long.class));

        // 图片轮播共 10800 毫秒，成片含交叠转场，只有 10000 毫秒。
        PlaybackReportDTO inRange = report("s1", 2, 600, 600, 10_500L);
        inRange.setReceivedAt(initialTime + 1_000);
        service.apply(USER, List.of(inRange));
        session.clearCache();
        assertEquals(10_500L, playback.get(2L, USER).positionMs());

        PlaybackReportDTO beyondEnd = report("s1", 2, 600, 600, 12_000L);
        beyondEnd.setReceivedAt(initialTime + 2_000);
        service.apply(USER, List.of(beyondEnd));
        session.clearCache();
        assertEquals(10_799L, playback.get(2L, USER).positionMs());
    }

    @Test
    void zeroMediaDurationSkipsHistoryAndBehaviorWrites() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);
        PlaybackReportDTO withPosition = report("s1", 1, 2_000, 2_000, 5_000L);
        long initialVersion = withPosition.getReceivedAt();
        service.apply(USER, List.of(withPosition));

        stubVideoDuration(1, 0);
        PlaybackReportDTO unusablePosition = report("s1", 1, 500, 500, 0L);
        unusablePosition.setReceivedAt(initialVersion + 2_000);
        service.apply(USER, List.of(unusablePosition));
        assertEquals(5_000L, resumePosition(1));
        assertEquals(ts(initialVersion), jdbc.queryForObject("SELECT view_time FROM user_view_history", LocalDateTime.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
        verifyNoInteractions(tagPreferences);

        stubVideoDuration(1, 10_000);
        PlaybackReportDTO reset = report("s1", 1, 2_000, 2_000, 0L);
        reset.setReceivedAt(initialVersion + 1_000);
        service.apply(USER, List.of(reset));

        assertEquals(0L, resumePosition(1));
        assertEquals(reset.getReceivedAt(), jdbc.queryForObject("SELECT reported_at FROM user_view_history", Long.class));
        assertEquals(ts(reset.getReceivedAt()),
                jdbc.queryForObject("SELECT view_time FROM user_view_history", LocalDateTime.class));
    }

    @Test
    void playIsRegisteredOncePerSession() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);

        PlaybackReportDTO first = report("s1", 1, 7_000, 7_000, 5_000L);
        PlaybackReportDTO second = report("s1", 1, 8_000, 8_000, 6_000L);
        second.setReceivedAt(first.getReceivedAt() + 1_000);
        service.apply(USER, List.of(first));
        service.apply(USER, List.of(second));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
        verify(tagPreferences, times(1)).addBehaviorScore(USER, 1L, EventEnum.PLAY);
        assertEquals(1L, playCount(1));
        // 版本更新的快照被接受, 位置更新
        assertEquals(6_000L, resumePosition(1));
    }

    @Test
    void rewatchInSameSessionKeepsOnePlayRow() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);
        PlaybackReportDTO first = report("s1", 1, 7_000, 7_000, 0L);
        PlaybackReportDTO second = report("s1", 1, 7_000, 7_000, 0L);
        // 同一会话内隔了半小时的第二次观看, 版本更新
        second.setReceivedAt(System.currentTimeMillis() + 30 * 60_000);

        service.apply(USER, List.of(first));
        service.apply(USER, List.of(second));

        // 会话期即播放量窗口: 同会话同内容至多一条 PLAY 行、一次计分、一次计数
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
        assertEquals(1L, playCount(1));
        verify(tagPreferences, times(1)).addBehaviorScore(USER, 1L, EventEnum.PLAY);
    }

    @Test
    void secondSessionCountsAgain() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);

        service.apply(USER, List.of(report("s1", 1, 7_000, 7_000, 0L)));
        // 会话过期后后端发新号, 再看再计
        service.apply(USER, List.of(report("s2", 1, 8_000, 8_000, 0L)));

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
        assertEquals(2L, playCount(1));
        verify(tagPreferences, times(2)).addBehaviorScore(USER, 1L, EventEnum.PLAY);
    }

    @Test
    void sameSessionConcurrentAppliesCountPlayOnce() throws Exception {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);
        PlaybackReportDTO report = report("s1", 1, 7_000, 7_000, 0L);
        PlaybackReportService second = newService(factory.openSession(true));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                ready.countDown();
                await(go);
                service.apply(USER, List.of(report));
            });
            Future<?> other = pool.submit(() -> {
                ready.countDown();
                await(go);
                second.apply(USER, List.of(report));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            first.get(5, TimeUnit.SECONDS);
            other.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
        assertEquals(1L, playCount(1));
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deleteRemovesRowPhysicallyAndLateReplayRecreatesIt() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);
        long t0 = System.currentTimeMillis();
        long t2 = t0 + 10_000;
        UserViewHistoryMapper historyMapper = session.getMapper(UserViewHistoryMapper.class);

        PlaybackReportDTO watched = report("s1", 1, 600, 600, 0L);
        watched.setReceivedAt(t0);
        service.apply(USER, List.of(watched));
        assertEquals(1, historyMapper.deleteOne(USER, 1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));

        // 旧批次重投: 物理删后行会重建(已接受的复活取舍), 接收时间沿用旧章
        PlaybackReportDTO replayed = report("s1", 1, 700, 700, 0L);
        replayed.setReceivedAt(t0);
        service.apply(USER, List.of(replayed));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));
        assertEquals(ts(t0),
                jdbc.queryForObject("SELECT view_time FROM user_view_history", LocalDateTime.class));

        // 新观看更新最近观看时间, 首次观看时间是重建行的插入时刻
        PlaybackReportDTO rewatched = report("s2", 1, 800, 800, 0L);
        rewatched.setReceivedAt(t2);
        service.apply(USER, List.of(rewatched));
        assertEquals(ts(t2),
                jdbc.queryForObject("SELECT view_time FROM user_view_history", LocalDateTime.class));
        LocalDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM user_view_history", LocalDateTime.class);
        assertTrue(createdAt.isAfter(ts(t0)));
    }

    @Test
    void clearAllRemovesRowsPhysically() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        insertContent(2, ContentTypeEnum.DYNAMIC, "published", 0);
        stubVideoDuration(1, 10_000);
        stubDynamicDuration(2, 10_000);
        UserViewHistoryMapper historyMapper = session.getMapper(UserViewHistoryMapper.class);

        service.apply(USER, List.of(
                report("s1", 1, 7_000, 7_000, 0L),
                report("s1", 2, 7_000, 7_000, 0L)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));

        assertEquals(2, historyMapper.deleteAll(USER));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));
    }

    @Test
    void missingOrDeletedContentSkipsEverything() {
        insertContent(3, ContentTypeEnum.VIDEO, "published", 1);

        service.apply(USER, List.of(
                report("s1", 999, 7_000, 7_000, 100L),
                report("s1", 3, 7_000, 7_000, 100L)));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
        verifyNoInteractions(tagPreferences);
    }

    @Test
    void missingMediaSkipsOnlyThatReport() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        insertContent(2, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(2, 10_000);

        service.apply(USER, List.of(
                report("s1", 1, 500, 500, 1_000L),
                report("s1", 2, 7_000, 7_000, 7_000L)));

        assertEquals(List.of(2L), jdbc.queryForList("SELECT content_id FROM user_view_history", Long.class));
        assertEquals(List.of("play"), jdbc.queryForList("SELECT event_type FROM user_content_behavior", String.class));
        assertEquals(0L, playCount(1));
        assertEquals(1L, playCount(2));
        verify(tagPreferences).addBehaviorScore(USER, 2L, EventEnum.PLAY);
        verifyNoMoreInteractions(tagPreferences);
    }

    @Test
    void bannedContentStillKeepsHistory() {
        insertContent(4, ContentTypeEnum.VIDEO, "banned", 0);
        stubVideoDuration(4, 10_000);

        service.apply(USER, List.of(report("s1", 4, 600, 600, 0L)));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
    }

    @Test
    void resumeAcceptsNewerVersionAndDraggedBackPosition() {
        insertContent(1, ContentTypeEnum.VIDEO, "published", 0);
        stubVideoDuration(1, 10_000);
        long t0 = System.currentTimeMillis();

        service.apply(USER, List.of(report("s1", 1, 1_000, 1_000, 5_000L)));
        assertEquals(5_000L, resumePosition(1));

        // 位置后退时仍接受更新版本的快照。
        PlaybackReportDTO draggedBack = report("s1", 1, 9_000, 9_000, 3_000L);
        draggedBack.setReceivedAt(t0 + 1_000);
        service.apply(USER, List.of(draggedBack));
        assertEquals(3_000L, resumePosition(1));

        // 较早的批次迟到，既不会覆盖续播位置，也不会重复登记同会话的 PLAY。
        PlaybackReportDTO older = report("s1", 1, 8_000, 8_000, 9_999L);
        older.setReceivedAt(t0 - 5_000);
        service.apply(USER, List.of(older));
        assertEquals(3_000L, resumePosition(1));
        assertEquals(1L, playCount(1));
        verify(tagPreferences, times(1)).addBehaviorScore(USER, 1L, EventEnum.PLAY);

        // 没有实际展示的快照不会覆盖进度。
        PlaybackReportDTO newer = report("s2", 1, 0, 0, 2_000L);
        newer.setReceivedAt(t0 + 2_000);
        service.apply(USER, List.of(newer));
        assertEquals(3_000L, resumePosition(1));
    }

    @Test
    void legacyWriterRowsWithoutSessionIdStillCoexist() {
        // 旧接口写入不带会话标识: NULL 不参与唯一键
        jdbc.update("INSERT INTO user_content_behavior (id, user_id, content_id, event_type) VALUES (1, 9, 1, 'play'), (2, 9, 1, 'play')");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_content_behavior", Integer.class));
    }
}
