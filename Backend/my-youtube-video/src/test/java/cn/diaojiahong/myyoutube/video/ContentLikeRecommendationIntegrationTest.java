package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.follow.cache.FollowSnapshotCache;
import cn.diaojiahong.myyoutube.like.mapper.ContentCommentLikeMapper;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.like.service.IContentLikeService;
import cn.diaojiahong.myyoutube.like.service.impl.ContentLikeServiceImpl;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.recommend.mapper.ContentDislikeMapper;
import cn.diaojiahong.myyoutube.recommend.mapper.ContentTagMapper;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.cf.ItemCfCalculator;
import cn.diaojiahong.myyoutube.recommend.cf.SimComputePool;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.recommend.service.ContentDislikeService;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import cn.diaojiahong.myyoutube.recommend.service.impl.ItemSimilarityServiceImpl;
import cn.diaojiahong.myyoutube.recommend.service.impl.UserTagPreferenceServiceImpl;
import cn.diaojiahong.myyoutube.recommend.service.impl.UserVideoBehaviorServiceImpl;
import cn.diaojiahong.myyoutube.share.service.IContentShareService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import cn.diaojiahong.myyoutube.video.controller.InternalVideoController;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ContentLikeRecommendationIntegrationTest.Config.class)
class ContentLikeRecommendationIntegrationTest {

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {ContentLikeMapper.class, UserVideoBehaviorMapper.class, ContentMapper.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return ContentTestDatabase.create("content-like-recommendation");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/UserTagPreferenceMapper.xml"),
                    new ClassPathResource("mapper/UserVideoBehaviorMapper.xml"),
                    new ClassPathResource("mapper/ItemSimilarityMapper.xml"));
            return ContentTestDatabase.sharedReads(factory.getObject());
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        UserTagPreferenceServiceImpl preferences() {
            return new UserTagPreferenceServiceImpl();
        }

        @Bean
        CounterBuffer counterBuffer() {
            return mock(CounterBuffer.class);
        }

        @Bean
        ContentServiceImpl contents() {
            return new ContentServiceImpl();
        }

        @Bean
        UserVideoBehaviorServiceImpl behaviorService() {
            return new UserVideoBehaviorServiceImpl();
        }

        @Bean
        ContentDislikeService dislikes(ContentAccessService access, ContentDislikeMapper dislikes,
                                       UserVideoBehaviorMapper behaviors, IUserTagPreferenceService preferences) {
            return new ContentDislikeService(access, dislikes, behaviors, preferences);
        }

        @Bean
        ItemSimilarityServiceImpl similarityService(IUserVideoBehaviorService behaviors, ContentLikeMapper likes,
                                                    IContentService contents, DataSourceTransactionManager manager) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            Cursor<?> cursor = mock(Cursor.class);
            doReturn(cursor).when(redis).scan(any(ScanOptions.class));
            return new ItemSimilarityServiceImpl(behaviors, likes, contents,
                    List.of(new ItemCfCalculator(new SimComputePool())), redis, new TransactionTemplate(manager));
        }

        @Bean
        ContentAccessService access() {
            return mock(ContentAccessService.class);
        }

        @Bean
        ContentLikeServiceImpl likes(ContentAccessService access, ContentLikeMapper likes, ContentTagMapper tags,
                                     IUserTagPreferenceService preferences,
                                     CounterBuffer counterBuffer) {
            return new ContentLikeServiceImpl(access, mock(ContentSummaryPort.class),
                    mock(StringRedisTemplate.class), mock(UserPort.class), mock(S3StorageUtil.class), likes,
                    mock(ContentCommentLikeMapper.class), mock(IContentCommentService.class), mock(RocketMQTemplate.class),
                    mock(FollowSnapshotCache.class), counterBuffer, tags, preferences);
        }
    }

    @Autowired private DataSource source;
    @Autowired private IContentLikeService likes;
    @Autowired private IUserTagPreferenceService preferences;
    @Autowired private ContentDislikeService dislikes;
    @Autowired private UserVideoBehaviorMapper behaviors;
    @Autowired private IItemSimilarityService similarityService;
    @Autowired private CounterBuffer counterBuffer;
    @Autowired private ContentAccessService access;
    @Autowired private DataSourceTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        UserContext.setUser("9");
        reset(counterBuffer);
        reset(access);
        when(access.readable(anyLong(), anyLong())).thenReturn(new Content().setAuthorId(8L));
        jdbc = new JdbcTemplate(source);
        String[] tables = {"content_like", "content_tag", "user_tag_preference", "user_video_behavior", "content_dislike",
                "item_similarity"};
        ContentTestDatabase.drop(jdbc, tables);
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        for (String table : tables) {
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`");
            String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
            if (!Boolean.getBoolean("content.integration")) {
                ddl = ddl.replace(" JSON NOT NULL", " TEXT NOT NULL").replace(" ENGINE=InnoDB", "")
                        .replace("KEY `idx_content_id`", "KEY `idx_" + table + "_content_id`");
            }
            jdbc.execute(ddl);
        }
        ContentTestDatabase.drop(jdbc, "content");
        jdbc.execute("CREATE TABLE content (id BIGINT PRIMARY KEY, type VARCHAR(16))");
    }

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void cancelDeletesRelationAndReversesOnlyTheOriginalTags() {
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (1, 20), (1, 30)");
        jdbc.update("INSERT INTO user_tag_preference (user_id, tag_id, score) VALUES (9, 20, 6)");
        likes.likeContent(1L);
        likes.likeContent(1L);
        assertTrue(likes.getLikeStatus(1L));
        assertEquals(List.of(10, 4), scores());
        assertEquals(4, jdbc.queryForObject(
                "SELECT credited_score FROM content_like WHERE user_id = 9 AND content_id = 1", Integer.class));

        jdbc.update("DELETE FROM content_tag WHERE content_id = 1");
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (1, 40)");
        likes.cancelLikeContent(1L);
        likes.cancelLikeContent(1L);

        assertFalse(likes.getLikeStatus(1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_like", Integer.class));
        assertEquals(List.of(6, 0), scores());
        verify(counterBuffer).add(CounterType.CONTENT_LIKE, 1L, 1);
        verify(counterBuffer).add(CounterType.CONTENT_LIKE, 1L, -1);
        verifyNoMoreInteractions(counterBuffer);

        likes.likeContent(1L);
        assertEquals(List.of(6, 0, 4), scores());
    }

    @Test
    void cancelUsesTheScoreRecordedWithTheLike() {
        jdbc.update("""
                INSERT INTO content_like (user_id, content_id, credited_tag_ids, credited_score)
                VALUES (9, 1, '["20"]', 6)
                """);
        jdbc.update("INSERT INTO user_tag_preference (user_id, tag_id, score) VALUES (9, 20, 16)");

        likes.cancelLikeContent(1L);

        assertFalse(likes.getLikeStatus(1L));
        assertEquals(List.of(10), scores());
    }

    @Test
    void cancelTreatsJsonNullTagIdsAsEmpty() {
        jdbc.update("""
                INSERT INTO content_like (user_id, content_id, credited_tag_ids, credited_score)
                VALUES (9, 1, 'null', 4)
                """);

        likes.cancelLikeContent(1L);

        assertFalse(likes.getLikeStatus(1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_like", Integer.class));
        assertTrue(scores().isEmpty());
        verify(counterBuffer).add(CounterType.CONTENT_LIKE, 1L, -1);
    }

    @Test
    void authorCannotDislikeOwnContent() {
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (3, 20)");
        when(access.readable(3L, 9L)).thenReturn(new Content().setAuthorId(9L));

        BizException error = assertThrows(BizException.class, () -> dislikes.mark(9L, 3L, 5_000L));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.getErrorCode());
        assertEquals("不能对自己的内容标记不感兴趣", error.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_dislike", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_video_behavior", Integer.class));
        assertTrue(scores().isEmpty());
    }

    @Test
    void createdAtComesFromTheDatabaseWhenNoTimeIsReported() {
        dislikes.mark(9L, 3L, 5_000L);

        LocalDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM user_video_behavior WHERE user_id = 9 AND content_id = 3",
                LocalDateTime.class);
        // 请求体没有时间字段, created_at 是数据库 CURRENT_TIMESTAMP 生成的时间
        LocalDateTime now = LocalDateTime.now();
        assertTrue(createdAt.isAfter(now.minusMinutes(1)));
        assertTrue(createdAt.isBefore(now.plusMinutes(1)));
    }

    @Test
    void dislikeDeductsOncePerCooldownAndExpiredRowsStopFiltering() {
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (3, 20), (3, 30), (4, 40)");
        long firstExpiry = dislikes.mark(9L, 3L, 5_000L);
        assertEquals(firstExpiry, dislikes.mark(9L, 3L, 9_000L));
        assertEquals(firstExpiry, dislikes.activeExpiry(9L, 3L));
        assertEquals(List.of(-4, -4), scores());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_video_behavior", Integer.class));

        jdbc.update("UPDATE content_dislike SET expires_at = 0 WHERE user_id = 9 AND content_id = 3");
        dislikes.mark(9L, 4L, null);
        assertEquals(List.of(4L), dislikes.activeContentIds(9L));
        assertEquals(List.of(), dislikes.activeContentIds(10L));
        assertNull(dislikes.activeExpiry(9L, 3L));

        long renewedExpiry = dislikes.mark(9L, 3L, 6_000L);
        assertEquals(renewedExpiry, dislikes.activeExpiry(9L, 3L));
        assertEquals(List.of(-8, -8, -4), scores());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM user_video_behavior", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDislikesReuseOneCooldownAndDeductOnce(boolean expired) throws Exception {
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (3, 20)");
        if (expired) {
            jdbc.update("INSERT INTO content_dislike (user_id, content_id, expires_at) VALUES (9, 3, 0)");
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> {
                start.await();
                return dislikes.mark(9L, 3L, 5_000L);
            });
            Future<Long> second = executor.submit(() -> {
                start.await();
                return dislikes.mark(9L, 3L, 9_000L);
            });
            start.countDown();

            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(-4), scores());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_video_behavior", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unreadableContentBlocksCancel() {
        jdbc.update("""
                INSERT INTO content_like (user_id, content_id, credited_tag_ids, credited_score)
                VALUES (9, 1, '["20"]', 4)
                """);
        jdbc.update("INSERT INTO user_tag_preference (user_id, tag_id, score) VALUES (9, 20, 4)");
        doThrow(new BizException(ErrorCode.NOT_FOUND, "内容不存在或不可访问")).when(access).readable(1L, 9L);

        assertThrows(BizException.class, () -> likes.cancelLikeContent(1L));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_like", Integer.class));
        assertEquals(List.of(4), scores());
        verifyNoInteractions(counterBuffer);
    }

    @Test
    void topPreferencesReturnsOnlyPositiveScores() {
        InternalVideoController controller = new InternalVideoController(
                mock(IContentTagService.class), mock(IContentService.class), mock(ContentUnderstandingMapper.class),
                mock(ContentLikeMapper.class), mock(ContentAccessService.class), mock(IContentShareService.class),
                mock(NotificationPublisher.class), preferences);
        jdbc.update("""
                INSERT INTO user_tag_preference (user_id, tag_id, score)
                VALUES (9, 20, -2), (9, 30, 0), (10, 20, 100)
                """);
        assertTrue(controller.topPreferences(9L, 20).isEmpty());

        jdbc.update("INSERT INTO user_tag_preference (user_id, tag_id, score) VALUES (9, 40, 2), (9, 50, 7)");

        assertEquals(List.of(
                new PreferencePort.TagPreferenceSummary(50L, 7.0),
                new PreferencePort.TagPreferenceSummary(40L, 2.0)), controller.topPreferences(9L, 20));
        assertEquals(List.of(-2, 0, 2, 7), scores());
    }

    @Test
    void recallDropsTheLikeButKeepsViewing() {
        LocalDateTime watched = LocalDateTime.now().minusHours(1).withNano(0);
        jdbc.update("""
                INSERT INTO user_video_behavior (id, user_id, content_id, event_type, created_at)
                VALUES (1, 9, 2, 'play', ?)
                """, watched);
        likes.likeContent(1L);
        likes.likeContent(2L);
        LocalDateTime since = watched.minusDays(1);
        assertEquals(List.of(1L, 2L), behaviors.recentPositiveSeeds(9L, since, 20).stream()
                .map(UserVideoBehaviorMapper.Seed::contentId).sorted().toList());

        likes.cancelLikeContent(1L);
        likes.cancelLikeContent(2L);

        assertEquals(List.of(new UserVideoBehaviorMapper.Seed(2L, watched)), behaviors.recentPositiveSeeds(9L, since, 20));
    }

    @Test
    void rolledBackLikeLeavesNeitherRelationNorScore() {
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (1, 20)");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            likes.likeContent(1L);
            status.setRollbackOnly();
        });

        assertFalse(likes.getLikeStatus(1L));
        assertTrue(scores().isEmpty());
        verifyNoInteractions(counterBuffer);
    }

    @Test
    void rebuildRemovesStaleNeighborsAfterLikeIsCancelled() {
        jdbc.update("INSERT INTO content (id, type) VALUES (1, 'video'), (2, 'video')");
        jdbc.update("INSERT INTO user_video_behavior (id, user_id, content_id, event_type) VALUES (1, 9, 2, 'play')");
        likes.likeContent(1L);
        similarityService.rebuildAll();
        assertEquals(List.of(8.0, 8.0), jdbc.queryForList("SELECT score FROM item_similarity", Double.class));

        likes.cancelLikeContent(1L);
        likes.likeContent(1L);
        likes.cancelLikeContent(1L);
        assertEquals(List.of(8.0, 8.0), jdbc.queryForList("SELECT score FROM item_similarity", Double.class));

        similarityService.rebuildAll();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM item_similarity", Integer.class));
    }

    private List<Integer> scores() {
        return jdbc.queryForList("SELECT score FROM user_tag_preference WHERE user_id = 9 ORDER BY tag_id", Integer.class);
    }
}
