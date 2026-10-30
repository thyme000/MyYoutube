package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.mapper.ContentStatisticsMapper;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursorQuery;
import cn.diaojiahong.myyoutube.content.service.AuthorContentPageService;
import cn.diaojiahong.myyoutube.content.service.AfterCommitNotifications;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.content.service.ContentSettingsService;
import cn.diaojiahong.myyoutube.content.service.ContentStatisticsService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import cn.diaojiahong.myyoutube.video.config.AuthorContentPageProperties;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentPublicationEventMapper;
import cn.diaojiahong.myyoutube.video.service.AudioHistoricalAuthorCountService;
import cn.diaojiahong.myyoutube.video.service.ContentObservation;
import cn.diaojiahong.myyoutube.video.service.PublicationEffectsService;
import cn.diaojiahong.myyoutube.video.service.ReviewDecisionService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ContentStatisticsIntegrationTest.Config.class)
class ContentStatisticsIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {ContentMapper.class, AudioMapper.class})
    @Import({ContentStatisticsService.class, ContentSettingsService.class, ContentAccessService.class,
            ReviewDecisionService.class, ContentServiceImpl.class, AuthorContentPageService.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return ContentTestDatabase.create("content-statistics");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
            return ContentTestDatabase.sharedReads(factory.getObject());
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TransactionTemplate transaction(DataSourceTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        PublicationEffectsService effects() {
            return mock(PublicationEffectsService.class);
        }

        @Bean
        ContentObservation observation() {
            return mock(ContentObservation.class);
        }

        @Bean
        ContentPipelineProperties properties() {
            return new ContentPipelineProperties();
        }

        @Bean
        PromptTemplates prompts() {
            return new PromptTemplates();
        }

        @Bean
        FrameProcessingSettings frames() {
            return new FrameProcessingSettings();
        }

        @Bean
        AuthorContentPageProperties pages() {
            var properties = new AuthorContentPageProperties();
            properties.setScanBudgetMs(5000);
            return properties;
        }

        @Bean
        AudioContentAccess audioAccess(AudioMapper audios) {
            return new AudioContentAccess(audios, mock(AfterCommitNotifications.class));
        }
    }

    @Autowired DataSource source;
    @Autowired ReviewDecisionService decisions;
    @Autowired ContentSettingsService settings;
    @Autowired ContentStatisticsService statistics;
    @Autowired ContentMapper contents;
    @Autowired AudioMapper audios;
    @Autowired ContentPublicationEventMapper events;
    @Autowired ContentStatisticsMapper summary;
    @Autowired AuthorContentPageService authorPages;
    @MockitoSpyBean IContentService pageContents;
    @Autowired PublicationEffectsService effects;
    @Autowired TransactionTemplate transaction;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        reset(effects);
        jdbc = new JdbcTemplate(source);
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        for (String table : List.of("content", "author_content_stat", "audio_author_usage", "audio",
                "content_understanding", "content_review_record", "content_publication_event")) {
            ContentTestDatabase.drop(jdbc, table);
            String quoted = List.of("content", "author_content_stat", "audio_author_usage", "audio").contains(table)
                    ? "`" + table + "`" : table;
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + quoted + " (");
            String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
            if (!Boolean.getBoolean("content.integration"))
                ddl = ddl.replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB", "");
            jdbc.execute(ddl);
        }
        jdbc.update("""
                INSERT INTO audio(id,uploader_id,request_id,request_fingerprint,source_object_key,
                    upload_size,upload_content_type,upload_deadline,processing_status,review_status)
                VALUES(20,9,'audio-20','fingerprint','uploads/20',100,'audio/wav',CURRENT_TIMESTAMP,'ready','approved')
                """);
    }

    private void pending(long id, long author, String type, String visibility, Long audioId) {
        jdbc.update("INSERT INTO content(id,author_id,type,title,status,visibility,audio_id) VALUES(?,?,?,'作品','review',?,?)",
                id, author, type, visibility, audioId);
        jdbc.update("""
                INSERT INTO content_understanding(content_id,pipeline_id,state,input_json,received,submitted_at)
                VALUES(?,?,'MANUAL_PENDING','{}',1,CURRENT_TIMESTAMP)
                """, id, id + 1000);
    }

    private void publish(long id) {
        assertTrue(decisions.manual(id, true, "核验通过", 99L));
    }

    private long published(long author, String type, String visibility) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(published_count),0) FROM author_content_stat
                WHERE author_id=? AND type=? AND visibility=?
                """, Long.class, author, type, visibility);
    }

    private long references(long author, String type, String visibility) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(reference_count),0) FROM audio_author_usage
                WHERE audio_id=20 AND author_id=? AND type=? AND visibility=?
                """, Long.class, author, type, visibility);
    }

    @Test
    void summaryAndListUseTheSameAuthorTypeAndVisibilityScope() {
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 7, "dynamic", "public", null);
        pending(3, 7, "dynamic", "private", 20L);
        pending(4, 7, "video", "public", 20L);
        pending(5, 7, "video", "private", null);
        pending(6, 8, "dynamic", "public", 20L);
        for (long id = 1; id <= 6; id++) publish(id);

        assertEquals(5, summary.displayCount(7, null, null));
        assertEquals(3, summary.displayCount(7, null, ContentVisibilityEnum.PUBLIC));
        assertEquals(3, summary.displayCount(7, ContentTypeEnum.DYNAMIC, null));
        assertEquals(2, summary.displayCount(7, ContentTypeEnum.DYNAMIC, ContentVisibilityEnum.PUBLIC));
        assertEquals(2, summary.displayCount(7, ContentTypeEnum.VIDEO, null));
        assertEquals(1, summary.displayCount(7, ContentTypeEnum.VIDEO, ContentVisibilityEnum.PUBLIC));
        assertEquals(1, summary.displayCount(8, null, null));
        assertEquals(0, summary.displayCount(999, null, null));
        assertAuthorPage(7L, 7, null, 5);
        assertAuthorPage(null, 7, "dynamic", 2);
        assertAuthorPage(9L, 7, "video", 1);

        jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
        assertEquals(2, summary.displayCount(7, null, null));
        assertEquals(1, summary.displayCount(7, null, ContentVisibilityEnum.PUBLIC));
        assertEquals(1, summary.displayCount(7, ContentTypeEnum.DYNAMIC, null));
        assertEquals(1, summary.displayCount(7, ContentTypeEnum.VIDEO, null));
        assertEquals(0, summary.displayCount(8, null, null));
        assertAuthorPage(7L, 7, null, 2);
        assertAuthorPage(9L, 7, "dynamic", 1);
        assertAuthorPage(9L, 7, "video", 0);
    }

    private void assertAuthorPage(Long viewer, long author, String type, long expected) {
        var query = new AuthorContentCursorQuery();
        query.setType(type);
        query.setPageSize(100);
        AuthorContentPageService.AuthorContentCandidates result = authorPages.list(viewer, author, query);
        assertEquals(expected, result.displayCount());
        assertEquals(expected, result.page().items().size());
        assertNull(result.page().nextCursor());
    }

    @Test
    void lazyBansDoNotSubtractTheRestrictedContributionTwice() {
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 7, "dynamic", "public", 20L);
        pending(3, 7, "dynamic", "public", null);
        for (long id = 1; id <= 3; id++) publish(id);
        assertEquals(3, summary.displayCount(7, null, ContentVisibilityEnum.PUBLIC));

        jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
        assertEquals(1, summary.displayCount(7, null, ContentVisibilityEnum.PUBLIC));
        assertTrue(decisions.banByAudio(new AudioContentBanMessage(1L, 20L, 1L)));
        assertEquals(2, published(7, "dynamic", "public"));
        assertEquals(1, references(7, "dynamic", "public"));
        assertEquals(1, summary.displayCount(7, null, ContentVisibilityEnum.PUBLIC));

        assertTrue(decisions.banByAudio(new AudioContentBanMessage(2L, 20L, 1L)));
        assertFalse(decisions.banByAudio(new AudioContentBanMessage(2L, 20L, 1L)));
        assertEquals(1, published(7, "dynamic", "public"));
        assertEquals(0, references(7, "dynamic", "public"));
        assertEquals(1, summary.displayCount(7, null, ContentVisibilityEnum.PUBLIC));
        assertAuthorPage(null, 7, null, 1);
    }

    @Test
    void privateAndSoftDeletedAudioRemainCountedButMissingAudioDoesNot() {
        pending(1, 7, "dynamic", "public", 20L);
        publish(1);
        jdbc.update("UPDATE audio SET visibility='private', is_delete=1 WHERE id=20");
        assertAuthorPage(null, 7, null, 1);
        jdbc.update("DELETE FROM audio WHERE id=20");
        assertAuthorPage(null, 7, null, 0);
    }

    @Test
    void visibilityAndDeletionUpdateTheDisplayedCountWithTheContent() {
        pending(1, 7, "dynamic", "public", 20L);
        publish(1);
        assertAuthorPage(null, 7, null, 1);
        settings.update(1L, 7L, new ContentSettingsService.Settings("private", 1));
        assertAuthorPage(null, 7, null, 0);
        assertAuthorPage(7L, 7, null, 1);
        settings.delete(1L, 7L);
        assertAuthorPage(7L, 7, null, 0);
    }

    enum PageChange {
        PUBLISH, DELETE, VISIBILITY, AUDIO_BAN
    }

    @ParameterizedTest
    @EnumSource(PageChange.class)
    @EnabledIfSystemProperty(named = "content.integration", matches = "true")
    void authorCountAndRowsShareASnapshotWhileTheNextRequestSeesCommittedChanges(PageChange change) throws Exception {
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 7, "dynamic", "public", 20L);
        publish(1);
        var query = new AuthorContentCursorQuery();
        query.setPageSize(100);

        // 计数完成后、列表开始前，让另一连接提交变更。
        doAnswer(call -> {
            CompletableFuture.runAsync(() -> {
                switch (change) {
                    case PUBLISH -> publish(2);
                    case DELETE -> settings.delete(1L, 7L);
                    case VISIBILITY -> settings.update(1L, 7L, new ContentSettingsService.Settings("private", 1));
                    case AUDIO_BAN -> jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
                }
            }).get(5, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(pageContents).list(any(Wrapper.class));

        AuthorContentPageService.AuthorContentCandidates result = authorPages.list(9L, 7, query);
        doCallRealMethod().when(pageContents).list(any(Wrapper.class));

        assertEquals(1L, result.displayCount());
        assertEquals(List.of(1L), result.page().items().stream().map(Content::getId).toList());
        assertAuthorPage(9L, 7, null, change == PageChange.PUBLISH ? 2 : 0);
    }

    @Test
    void publishedAuthorsRemainHistoricalUsersAfterAllTheirWorksAreRemoved() {
        var history = new AudioHistoricalAuthorCountService(events, audios, summary, transaction);
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 7, "video", "private", 20L);
        pending(3, 8, "dynamic", "public", 20L);
        publish(1);
        publish(2);
        history.apply(20L);
        assertEquals(1L, audios.selectById(20L).getHistoricalAuthorCount());
        settings.delete(1L, 7L);
        settings.delete(2L, 7L);
        history.apply(20L);
        assertEquals(1L, audios.selectById(20L).getHistoricalAuthorCount());

        publish(3);
        history.apply(20L);
        assertEquals(2L, audios.selectById(20L).getHistoricalAuthorCount());
        jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
        assertTrue(decisions.banByAudio(new AudioContentBanMessage(3L, 20L, 1L)));
        history.apply(20L);
        assertEquals(2L, audios.selectById(20L).getHistoricalAuthorCount());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM content_publication_event WHERE audio_count_done=0",
                        Long.class));
    }

    @Test @EnabledIfSystemProperty(named = "content.integration", matches = "true")
    void differentAuthorsShareThePublicationAudioLockButABanWaitsForCommit() throws Exception {
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 8, "dynamic", "public", 20L);
        var firstPublished = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(tx -> {
            publish(1);
            firstPublished.countDown();
            await(releaseFirst);
        }));
        CompletableFuture<Void> ban = null;
        try {
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
            CompletableFuture.runAsync(() -> publish(2)).get(5, TimeUnit.SECONDS);
            ban = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(tx ->
                    jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20")));
            CompletableFuture<Void> waitingBan = ban;
            assertThrows(TimeoutException.class, () -> waitingBan.get(200, TimeUnit.MILLISECONDS));
        } finally {
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            if (ban != null) ban.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE status='published'", Long.class));
        assertEquals(0, summary.displayCount(7L, null, null));
        assertEquals(0, summary.displayCount(8L, null, null));
    }

    @ParameterizedTest
    @CsvSource({"dynamic,public", "dynamic,private", "video,public", "video,private"})
    void publicationGroupsByTypeAndVisibilityAndDuplicateReviewCannotIncrement(String type, String visibility) {
        pending(1, 7, type, visibility, 20L);
        assertEquals(0, published(7, type, visibility));
        publish(1);
        assertEquals(1, published(7, type, visibility));
        assertEquals(1, references(7, type, visibility));
        assertThrows(BizException.class, () -> decisions.manual(1L, true, "重复审核", 99L));
        assertEquals(1, published(7, type, visibility));
        assertEquals(1, references(7, type, visibility));
    }

    @Test
    void contentWithoutAudioCountsButRejectedAndDeletedPendingContentLeaveNoHistory() {
        pending(1, 7, "video", "public", null);
        pending(2, 7, "dynamic", "public", 20L);
        pending(3, 7, "dynamic", "private", 20L);
        publish(1);
        assertTrue(decisions.manual(2L, false, "不通过", 99L));
        settings.delete(3L, 7L);
        assertEquals(1, published(7, "video", "public"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audio_author_usage", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM author_content_stat", Long.class));
    }

    @Test
    void visibilityMovesBothGroupsAndCommentChangeDoesNotChangeCounts() {
        pending(1, 7, "dynamic", "public", 20L);
        publish(1);
        settings.update(1L, 7L, new ContentSettingsService.Settings("private", 1));
        settings.update(1L, 7L, new ContentSettingsService.Settings("private", 0));
        assertEquals(0, published(7, "dynamic", "public"));
        assertEquals(0, references(7, "dynamic", "public"));
        assertEquals(1, published(7, "dynamic", "private"));
        assertEquals(1, references(7, "dynamic", "private"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audio_author_usage", Long.class));
        settings.update(1L, 7L, new ContentSettingsService.Settings("public", 1));
        assertEquals(1, published(7, "dynamic", "public"));
        assertEquals(1, references(7, "dynamic", "public"));
        assertEquals(0, references(7, "dynamic", "private"));
    }

    enum Removal {
        DELETE, MANUAL_BAN, AUDIO_BAN
    }

    @Test
    void multipleWorksAccumulateAndHistoricalAuthorsDeduplicateAcrossGroups() {
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 7, "dynamic", "public", 20L);
        pending(3, 7, "video", "private", 20L);
        pending(4, 8, "video", "public", 20L);
        for (long id = 1; id <= 4; id++) publish(id);
        assertEquals(2, published(7, "dynamic", "public"));
        assertEquals(2, references(7, "dynamic", "public"));
        assertEquals(1, published(7, "video", "private"));
        assertEquals(1, references(8, "video", "public"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT author_id) FROM audio_author_usage", Long.class));

        for (long id = 1; id <= 3; id++) settings.delete(id, 7L);
        assertEquals(0, published(7, "dynamic", "public"));
        assertEquals(0, references(7, "video", "private"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT author_id) FROM audio_author_usage", Long.class));
    }

    @Test
    void unauthorizedSettingsAndDeletionCannotChangeStatistics() {
        pending(1, 7, "dynamic", "public", 20L);
        publish(1);
        assertThrows(BizException.class, () -> settings.delete(1L, 8L));
        assertThrows(
                BizException.class,
                () -> settings.update(1L, 8L, new ContentSettingsService.Settings("private", 1)));
        assertEquals(1, published(7, "dynamic", "public"));
        assertEquals(1, references(7, "dynamic", "public"));
        assertEquals(0, published(7, "dynamic", "private"));
    }

    @ParameterizedTest @EnumSource(Removal.class)
    void leavingPublishedDecrementsOnceAndRetainsZeroHistory(Removal removal) {
        pending(1, 7, "dynamic", "private", 20L);
        publish(1);
        switch (removal) {
            case DELETE -> {
                settings.delete(1L, 7L);
                assertThrows(BizException.class, () -> settings.delete(1L, 7L));
            }
            case MANUAL_BAN -> {
                assertTrue(decisions.banPublished(1L, "封禁", 99L));
                assertFalse(decisions.banPublished(1L, "重复封禁", 99L));
            }
            case AUDIO_BAN -> {
                jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
                var message = new AudioContentBanMessage(1L, 20L, 1L);
                assertTrue(decisions.banByAudio(message));
                assertFalse(decisions.banByAudio(message));
            }
        }
        assertEquals(0, published(7, "dynamic", "private"));
        assertEquals(0, references(7, "dynamic", "private"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT author_id) FROM audio_author_usage", Long.class));
    }

    @Test
    void lazyBanOfPendingContentCreatesNoCountOrHistoricalUsage() {
        pending(1, 7, "dynamic", "public", 20L);
        jdbc.update("UPDATE audio SET review_status='banned' WHERE id=20");
        assertTrue(decisions.banByAudio(new AudioContentBanMessage(1L, 20L, 1L)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM author_content_stat", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audio_author_usage", Long.class));
    }

    @Test
    void failedTransactionRollsBackContentBothCountsAndEvent() {
        pending(1, 7, "dynamic", "public", 20L);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            publish(1);
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(ContentStatusEnum.REVIEW, contents.selectById(1L).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM author_content_stat", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audio_author_usage", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_event", Long.class));
        verifyNoInteractions(effects);
    }

    @Test
    void missingReferenceCounterRollsBackDeletionAndAuthorDecrement() {
        pending(1, 7, "dynamic", "public", 20L);
        publish(1);
        reset(effects);
        jdbc.update("DELETE FROM audio_author_usage");
        assertThrows(IllegalStateException.class, () -> settings.delete(1L, 7L));
        assertEquals(0, contents.selectById(1L).getIsDelete());
        assertEquals(1, published(7, "dynamic", "public"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_event", Long.class));
        verifyNoInteractions(effects);
    }

    @Test
    void statisticWritesRequireTheContentTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> statistics.delete(new Content()));
    }

    @Test
    void oppositeVisibilityChangesUseStableGroupLockOrder() throws Exception {
        pending(1, 7, "dynamic", "public", 20L);
        pending(2, 7, "dynamic", "private", 20L);
        publish(1);
        publish(2);
        var start = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> flip(start, 1L, true));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> flip(start, 2L, false));
        start.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        for (String visibility : List.of("public", "private")) {
            assertEquals(1, published(7, "dynamic", visibility));
            assertEquals(1, references(7, "dynamic", visibility));
        }
    }

    private void flip(CountDownLatch start, long id, boolean publicInitially) {
        await(start);
        for (int round = 0; round < 20; round++) {
            String visibility = (round % 2 == 0) == publicInitially ? "private" : "public";
            settings.update(id, 7L, new ContentSettingsService.Settings(visibility, 1));
        }
    }

    @Test
    void concurrentAuthorsPublishingOneAudioKeepSeparateContributions() throws Exception {
        for (long id = 1; id <= 8; id++) pending(id, id, "dynamic", "public", 20L);
        var start = new CountDownLatch(1);
        List<CompletableFuture<Void>> publications = new ArrayList<>();
        for (long id = 1; id <= 8; id++) {
            long contentId = id;
            publications.add(CompletableFuture.runAsync(() -> {
                await(start);
                publish(contentId);
            }));
        }
        start.countDown();
        CompletableFuture.allOf(publications.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertEquals(8, jdbc.queryForObject("SELECT SUM(published_count) FROM author_content_stat", Long.class));
        assertEquals(8, jdbc.queryForObject("SELECT SUM(reference_count) FROM audio_author_usage", Long.class));
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(DISTINCT author_id) FROM audio_author_usage", Long.class));
    }

    private static void await(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
