package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.collect.mapper.ContentCollectMapper;
import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursorQuery;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursor;
import com.google.gson.JsonObject;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.AuthorContentCursorVO;
import cn.diaojiahong.myyoutube.content.controller.ContentController;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.mapper.ContentStatisticsMapper;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.service.impl.*;
import cn.diaojiahong.myyoutube.content.strategy.*;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.notification.service.impl.UserNotificationServiceImpl;
import cn.diaojiahong.myyoutube.notification.mapper.UserNotificationMapper;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.recommend.service.*;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort.SummaryQuery;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.controller.InternalVideoController;
import cn.diaojiahong.myyoutube.video.port.ContentSummaryPortImpl;
import cn.diaojiahong.myyoutube.video.mapper.ContentReviewRecordMapper;
import cn.diaojiahong.myyoutube.video.config.AuthorContentPageProperties;
import cn.diaojiahong.myyoutube.notification.support.ReviewFeedback;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.util.*;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(ContentListsIntegrationTest.Config.class)
class ContentListsIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = {ContentMapper.class, ContentCollectMapper.class, UserViewHistoryMapper.class, ContentLikeMapper.class, UserNotificationMapper.class})
    static class Config {
        @Bean DataSource ds() { return new DriverManagerDataSource("jdbc:h2:mem:content-lists;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            var pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            factory.setPlugins(pagination); return factory.getObject();
        }
        @Bean IContentService contents() { return new ContentServiceImpl(); }
        @Bean IContentCommentService comments() { return mock(IContentCommentService.class); }
        @Bean UserNotificationServiceImpl notifications(ContentMapper mapper, IContentCommentService comments) {
            return new UserNotificationServiceImpl(new ContentAccessService(mapper,mock(AudioContentAccess.class)),mock(UserPort.class),comments);
        }
    }
    @Autowired DataSource ds;
    @Autowired ContentMapper contents;
    @Autowired ContentStatisticsMapper statistics;
    @Autowired IContentService contentService;
    @Autowired ContentCollectMapper collects;
    @Autowired UserViewHistoryMapper history;
    @Autowired ContentLikeMapper likes;
    @Autowired IContentCommentService comments;
    @Autowired UserNotificationServiceImpl notifications;
    JdbcTemplate jdbc;
    ContentSummaryPortImpl summaries;
    ContentFeedServiceImpl feeds;
    final AudioContentAccess audioAccess = mock(AudioContentAccess.class);
    final ContentReviewRecordMapper reviewRecords = mock(ContentReviewRecordMapper.class);
    AuthorContentPageProperties pageProperties;
    IContentService pageStore;

    @BeforeEach void setup() {
        reset(audioAccess, reviewRecords);
        pageProperties = new AuthorContentPageProperties();
        pageProperties.setScanBudgetMs(5000);
        pageStore = spy(contentService);
        UserContext.setUser("7"); jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE content(id BIGINT PRIMARY KEY,type VARCHAR(16),author_id BIGINT,audio_id BIGINT,title VARCHAR(100),description TEXT,cover_object_key VARCHAR(512),category_code VARCHAR(32),tags TEXT,allow_comment INT,region VARCHAR(32),is_delete INT DEFAULT 0,status VARCHAR(16),visibility VARCHAR(16),index_version BIGINT NOT NULL DEFAULT 1,play_count BIGINT DEFAULT 0,like_count BIGINT DEFAULT 0,collect_count BIGINT DEFAULT 0,comment_count BIGINT DEFAULT 0,share_count BIGINT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE author_content_stat(author_id BIGINT,type VARCHAR(16),visibility VARCHAR(16),published_count BIGINT,PRIMARY KEY(author_id,type,visibility))");
        jdbc.execute("CREATE TABLE audio_author_usage(audio_id BIGINT,author_id BIGINT,type VARCHAR(16),visibility VARCHAR(16),reference_count BIGINT,PRIMARY KEY(audio_id,author_id,type,visibility))");
        jdbc.execute("CREATE TABLE audio(id BIGINT PRIMARY KEY,review_status VARCHAR(16))");
        jdbc.execute("CREATE TABLE content_collect(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,content_id BIGINT,create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE content_like(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,content_id BIGINT,is_liked INT,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE user_view_history(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,content_id BIGINT,view_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE user_notification(id BIGINT AUTO_INCREMENT PRIMARY KEY,receiver_id BIGINT,sender_id BIGINT,type VARCHAR(32),title VARCHAR(128),content VARCHAR(500),ref_type VARCHAR(16),ref_id BIGINT,extra_json TEXT,is_read INT,create_time TIMESTAMP)");
        for (int id = 1; id <= 8; id++) {
            jdbc.update("INSERT INTO content(id,type,author_id,title,description,cover_object_key,allow_comment,status,visibility,is_delete,create_time) VALUES(?,?,?,?,?,?,0,?,?,?,CURRENT_TIMESTAMP)",
                    id, Set.of(1,4,6,7).contains(id) ? "video" : "dynamic", Set.of(4,8).contains(id) ? 9 : 7,
                    "标题"+id, "简介"+id, "cover"+id, id==5 ? "review" : id==6 ? "banned" : "published",
                    Set.of(3,4).contains(id) ? "private" : "public", id==7 ? 1 : 0);
            jdbc.update("INSERT INTO content_collect(user_id,content_id,create_time) VALUES(7,?,CURRENT_TIMESTAMP)", id);
            jdbc.update("INSERT INTO content_like(user_id,content_id,is_liked,update_time) VALUES(7,?,1,CURRENT_TIMESTAMP)", id);
            jdbc.update("INSERT INTO user_view_history(user_id,content_id,view_time) VALUES(7,?,CURRENT_TIMESTAMP)", id);
        }
        seedStatistics();
        var registry = new ContentTypeStrategyRegistry(Arrays.stream(ContentTypeEnum.values()).map(type -> {
            var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(type);
            when(strategy.loadMedia(anyList())).thenAnswer(call -> call.<List<Long>>getArgument(0).stream()
                    .collect(Collectors.toMap(id -> id, id -> type==ContentTypeEnum.VIDEO
                            ? new ContentMedia("video",new VideoMedia("video"+id,9000),null)
                            : new ContentMedia("dynamic",null,new DynamicMedia("vertical",1080,1920,"cut",0,
                            List.of(new DynamicMedia.Image("cover"+id,0,3000,0.5,0.5)),null,3000)))));
            return strategy;
        }).toList());
        summaries = new ContentSummaryPortImpl(contents,registry,audioAccess);
        feeds = new ContentFeedServiceImpl(contentService,likes,mock(IUserFollowService.class),mock(UserPort.class),
                mock(S3StorageUtil.class),mock(RecommendPort.class),registry,audioAccess,reviewRecords,
                new AuthorContentPageService(pageStore, audioAccess, statistics, pageProperties));
    }

    @AfterEach void clear() { UserContext.removeUser(); }

    @Test void blockedAudioIsRemovedFromSummariesAndAuthorPagesButOwnerManagementKeepsRestrictedRecords() {
        when(audioAccess.blocked(anyList())).thenReturn(Set.of(2L, 3L));
        assertEquals(List.of(1L), summaries.listByIdsOrdered(new SummaryQuery(List.of("2", "1", "3"), 7L))
                .stream().map(s -> s.getId()).toList());
        var query = new ContentPageQuery(); query.setType("dynamic");
        assertTrue(feeds.byAuthor(7L, 7L, authorQuery("dynamic", 10)).page().items().isEmpty());
        var mine = feeds.mine(7L, query).getList();
        assertEquals(3, mine.size());
        for (var card : mine) if (Set.of("2", "3").contains(card.getId())) {
            assertEquals("banned", card.getStatus()); assertNull(card.getCoverUrl()); assertNull(card.getVideoUrl());
            assertEquals(ReviewFeedback.audioBannedMessage(), card.getRestrictionReason());
        }
        verifyNoInteractions(reviewRecords);
    }

    @Test void storedAudioBanKeepsTheSameOwnerExplanationWhileManualBansStayGeneric() {
        jdbc.update("UPDATE content SET status='banned' WHERE id=2");
        when(reviewRecords.audioBannedContentIds(anyCollection())).thenReturn(Set.of(2L));
        var mine = feeds.mine(7L, new ContentPageQuery()).getList();
        var audio = mine.stream().filter(c -> "2".equals(c.getId())).findFirst().orElseThrow();
        var manual = mine.stream().filter(c -> "6".equals(c.getId())).findFirst().orElseThrow();
        assertEquals(ReviewFeedback.audioBannedMessage(), audio.getRestrictionReason());
        assertEquals(ReviewFeedback.message("内容", false), manual.getRestrictionReason());
        assertNull(audio.getCoverUrl()); assertNull(audio.getVideoUrl());
        assertTrue(mine.stream().filter(c -> "published".equals(c.getStatus())).allMatch(c -> c.getRestrictionReason() == null));
        verify(reviewRecords).audioBannedContentIds(argThat(ids -> ids.size() == 2 && ids.containsAll(Set.of(2L, 6L))));
    }

    @Test void summariesPreserveOrderAndDuplicatesAndReadCurrentPermissions() {
        var ids = List.of("3","2","1","6","7","5","3");
        assertEquals(List.of(3L,2L,1L,3L), summaries.listByIdsOrdered(new SummaryQuery(ids,7L)).stream().map(s -> s.getId()).toList());
        assertEquals(List.of(2L,1L), summaries.listByIdsOrdered(new SummaryQuery(ids,null)).stream().map(s -> s.getId()).toList());
        jdbc.update("UPDATE content SET visibility='private' WHERE id=2");
        assertEquals(List.of(1L), summaries.listByIdsOrdered(new SummaryQuery(ids,null)).stream().map(s -> s.getId()).toList());
        var card = summaries.listByIdsOrdered(new SummaryQuery(List.of("2"),7L)).get(0);
        assertEquals("简介2",card.getDescription()); assertEquals(0,card.getAllowComment()); assertEquals("cover2",card.getCoverUrl());
    }

    @Test void personalListsFilterBeforePaginationAndUseSamePredicateForCounts() {
        assertEquals(List.of(8L,3L), collects.selectCollectIds(7L,"dynamic",0,2));
        assertEquals(List.of(2L), collects.selectCollectIds(7L,"dynamic",2,2));
        assertEquals(3,collects.selectCollectCount(7L,"dynamic"));
        assertEquals(List.of(8L,3L,2L,1L),history.selectHistoryIds(7L,null,0,20));
        assertEquals(4,history.selectHistoryCount(7L,null));
        assertEquals(List.of(1L),history.selectHistoryIds(7L,"video",0,20));
        assertEquals(1,history.selectHistoryCount(7L,"video"));
        assertEquals(List.of(8L,4L,2L,1L),likes.selectListByUserId(7L,9L,null,0,20));
        assertEquals(4,likes.selectUserLikedCount(7L,9L,null));
        jdbc.update("UPDATE content SET visibility='private' WHERE id=8");
        assertEquals(2,collects.selectCollectCount(7L,"dynamic"));
        assertEquals(3,history.selectHistoryCount(7L,null));
        assertEquals(3,likes.selectUserLikedCount(7L,7L,null));
    }

    @Test void mineAndAuthorPagesSupportMediaTypeAndKeepOwnerVisibilityRules() {
        var query = new ContentPageQuery(); query.setType("dynamic");
        assertEquals(3,feeds.mine(7L,query).getTotal());
        assertEquals(2,feeds.byAuthor(7L,7L,authorQuery("dynamic", 10)).displayCount());
        assertEquals(1,feeds.byAuthor(9L,7L,authorQuery(query.getType(), 10)).displayCount());
        AuthorContentCursorQuery videoQuery = authorQuery("VIDEO", 10);
        assertEquals(1, feeds.byAuthor(9L, 7L, videoQuery).displayCount());
        assertEquals("video", videoQuery.getType());
        query.setType("note"); assertThrows(BizException.class, () -> feeds.mine(7L,query));
        query.setType(null); query.setPageSize(0); assertThrows(BizException.class, () -> feeds.mine(7L,query));
        query.setPageSize(100); query.setPageNo(Integer.MAX_VALUE); assertThrows(BizException.class, () -> feeds.mine(7L,query));
    }

    private AuthorContentCursorQuery authorQuery(int size) {
        return authorQuery(null, size);
    }

    private AuthorContentCursorQuery authorQuery(String type, int size) {
        AuthorContentCursorQuery query = new AuthorContentCursorQuery();
        query.setType(type);
        query.setPageSize(size);
        return query;
    }

    private void seedStatistics() {
        jdbc.update("DELETE FROM author_content_stat");
        jdbc.update("DELETE FROM audio_author_usage");
        jdbc.update("""
                INSERT INTO author_content_stat
                SELECT author_id,type,visibility,COUNT(*) FROM content
                WHERE status='published' AND is_delete=0 GROUP BY author_id,type,visibility
                """);
        jdbc.update("""
                INSERT INTO audio_author_usage
                SELECT audio_id,author_id,type,visibility,COUNT(*) FROM content
                WHERE status='published' AND is_delete=0 AND audio_id IS NOT NULL
                GROUP BY audio_id,author_id,type,visibility
                """);
    }

    private void authorWorks(int count, Set<Long> blocked) {
        LocalDateTime time = LocalDateTime.of(2026, 10, 1, 0, 0);
        for (long id = 101; id <= 100 + count; id++) {
            jdbc.update("INSERT INTO audio(id,review_status) VALUES(?,?)", id + 10000, blocked.contains(id) ? "banned" : "approved");
            jdbc.update("INSERT INTO content(id,type,author_id,audio_id,title,status,visibility,is_delete,create_time) VALUES(?,'dynamic',42,?,'作品','published','public',0,?)", id, id + 10000, time);
        }
        seedStatistics();
        when(audioAccess.blocked(anyList())).thenAnswer(call -> call.<List<Content>>getArgument(0).stream()
                .map(Content::getId).filter(blocked::contains).collect(Collectors.toSet()));
    }

    @Test void summaryCountDoesNotScanRemainingWorksAndListKeepsStableOrder() {
        authorWorks(8, Set.of(108L, 104L));
        AuthorContentCursorVO page = feeds.byAuthor(9L, 42L, authorQuery(2));
        assertEquals(6, page.displayCount());
        assertNotNull(page.page().nextCursor());
        assertEquals(List.of("107", "106"), page.page().items().stream().map(card -> card.getId()).toList());
        verify(pageStore, times(2)).list(any(Wrapper.class));
        verify(pageStore, never()).count(any(Wrapper.class));
        verify(audioAccess, times(2)).blocked(anyList());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE author_id=42 AND status='published'", Integer.class));
    }

    @Test void authorEndpointReturnsCursorPageAndDisplayCountSeparately() throws Exception {
        authorWorks(8, Set.of(108L, 104L));
        ContentController controller = new ContentController(feeds, mock(ContentReadService.class), mock(ContentSettingsService.class),
                mock(ContentDownloadService.class), mock(ContentPlaybackService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String response = mvc.perform(get("/contents/author/42").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.pages").doesNotExist())
                .andExpect(jsonPath("$.data.page.nextCursor").isString())
                .andExpect(jsonPath("$.data.displayCount").value(6))
                .andExpect(jsonPath("$.data.page.items[0].id").value("107"))
                .andReturn().getResponse().getContentAsString();
        String next = JsonUtil.fromJson(response, JsonObject.class).getAsJsonObject("data")
                .getAsJsonObject("page").get("nextCursor").getAsString();
        mvc.perform(get("/contents/author/42").param("pageSize", "2").param("cursor", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayCount").value(6))
                .andExpect(jsonPath("$.data.page.items[0].id").value("105"));
        verify(pageStore, never()).count(any(Wrapper.class));
    }

    @Test void summaryCountExcludesBannedReferencesEvenWhenFilteringIsRare() {
        authorWorks(30, Set.of(130L));
        assertEquals(29, feeds.byAuthor(9L, 42L, authorQuery(10)).displayCount());
        verify(pageStore, times(2)).list(any(Wrapper.class));
        verify(pageStore, never()).count(any(Wrapper.class));
    }

    @Test void countRemainsAccurateWhenPageRefillReachesItsLimit() {
        authorWorks(50, Set.of(150L, 149L, 148L, 147L, 146L));
        pageProperties.setScanLimit(10);
        AuthorContentCursorVO page = feeds.byAuthor(9L, 42L, authorQuery(10));
        assertEquals(45, page.displayCount());
        assertEquals(5, page.page().items().size());
        assertNotNull(page.page().nextCursor());
        verify(pageStore, times(1)).list(any(Wrapper.class));
    }

    @Test void laterPagesDoNotTreatTheirRemainingCandidatesAsTheAuthorTotal() {
        authorWorks(8, Set.of(108L, 104L));
        AuthorContentCursorQuery query = authorQuery(2);
        query.setCursor(feeds.byAuthor(9L, 42L, query).page().nextCursor());
        clearInvocations(pageStore);
        assertEquals(6, feeds.byAuthor(9L, 42L, query).displayCount());
        verify(pageStore, never()).count(any(Wrapper.class));
    }

    @Test void allVisibleContentsCanBeTraversedWithTheWholeScopeCountOnEveryPage() {
        authorWorks(8, Set.of(108L, 104L));
        AuthorContentCursorQuery query = authorQuery(2);
        AuthorContentCursorVO first = feeds.byAuthor(9L, 42L, query);
        assertEquals(6, first.displayCount());
        AuthorContentCursor cursor = AuthorContentCursor.decode(first.page().nextCursor(), 42, 9L, null);
        assertEquals(106, cursor.contentId());

        List<String> ids = new ArrayList<>(first.page().items().stream().map(card -> card.getId()).toList());
        String next = first.page().nextCursor();
        while (next != null) {
            query.setCursor(next);
            AuthorContentCursorVO page = feeds.byAuthor(9L, 42L, query);
            assertEquals(6, page.displayCount());
            ids.addAll(page.page().items().stream().map(card -> card.getId()).toList());
            next = page.page().nextCursor();
        }
        assertEquals(List.of("107", "106", "105", "103", "102", "101"), ids);
        verify(pageStore, never()).count(any(Wrapper.class));
    }

    @Test void aFullyFilteredBatchCanContinueAndAnExhaustedSmallResultNeedsNoRefill() {
        authorWorks(8, Set.of(108L, 107L));
        pageProperties.setScanLimit(2);
        AuthorContentCursorQuery query = authorQuery(2);
        AuthorContentCursorVO first = feeds.byAuthor(9L, 42L, query);
        assertTrue(first.page().items().isEmpty());
        assertNotNull(first.page().nextCursor());
        assertEquals(6, first.displayCount());
        query.setCursor(first.page().nextCursor());
        assertEquals(List.of("106", "105"), feeds.byAuthor(9L, 42L, query).page().items().stream().map(card -> card.getId()).toList());

        clearInvocations(pageStore);
        query = authorQuery(10);
        AuthorContentCursorVO exhausted = feeds.byAuthor(9L, 7L, query);
        assertEquals(2, exhausted.displayCount());
        assertNull(exhausted.page().nextCursor());
        verify(pageStore, times(1)).list(any(Wrapper.class));
    }

    @Test void continuationRechecksCurrentVisibilityAndSurvivesRemovedCursorRows() {
        authorWorks(8, Set.of());
        AuthorContentCursorQuery query = authorQuery(2);
        query.setCursor(feeds.byAuthor(9L, 42L, query).page().nextCursor());
        jdbc.update("DELETE FROM content WHERE id=107");
        jdbc.update("UPDATE content SET visibility='private' WHERE id=106");
        jdbc.update("INSERT INTO content(id,type,author_id,title,status,visibility,is_delete,create_time) VALUES(109,'dynamic',42,'新作品','published','public',0,CURRENT_TIMESTAMP)");
        seedStatistics();
        AuthorContentCursorVO next = feeds.byAuthor(9L, 42L, query);
        assertEquals(List.of("105", "104"), next.page().items().stream().map(card -> card.getId()).toList());
        assertEquals(7, next.displayCount());
    }

    @Test void rejectsCursorsFromAnotherViewerAuthorOrTypeBeforeQuerying() {
        authorWorks(8, Set.of());
        AuthorContentCursorQuery query = authorQuery(2);
        query.setCursor(feeds.byAuthor(42L, 42L, query).page().nextCursor());
        clearInvocations(pageStore);
        assertThrows(BizException.class, () -> feeds.byAuthor(9L, 42L, query));
        assertThrows(BizException.class, () -> feeds.byAuthor(42L, 43L, query));
        query.setType("dynamic");
        assertThrows(BizException.class, () -> feeds.byAuthor(42L, 42L, query));
        verifyNoInteractions(pageStore);
    }

    @Test void summaryPreservesOwnerVisibilityAndMediaTypeFilters() {
        authorWorks(8, Set.of(108L, 104L));
        jdbc.update("UPDATE content SET visibility='private' WHERE id IN (108,107)");
        jdbc.update("UPDATE content SET type='video' WHERE id IN (106,105)");
        jdbc.update("UPDATE content SET is_delete=1 WHERE id=103");
        jdbc.update("UPDATE content SET status='review' WHERE id=102");
        seedStatistics();
        AuthorContentCursorQuery query = authorQuery(2);
        query.setType("dynamic");
        assertEquals(2, feeds.byAuthor(42L, 42L, query).displayCount());
        assertEquals(1, feeds.byAuthor(9L, 42L, query).displayCount());
    }

    @Test void missingAudioReferencesAreAlsoExcludedFromTheSummaryCount() {
        authorWorks(30, Set.of(130L, 129L, 128L, 127L, 126L));
        jdbc.update("DELETE FROM audio WHERE review_status='banned'");
        assertEquals(25, feeds.byAuthor(9L, 42L, authorQuery(10)).displayCount());
    }

    @Test void forwardingAndShareCounterRecheckCurrentStateWithAuthenticatedViewer() {
        var counter = mock(CounterBuffer.class);
        var internal = new InternalVideoController(mock(IContentTagService.class),contentService,
                mock(cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper.class),likes,
                new ContentAccessService(contents,mock(AudioContentAccess.class)),counter,mock(IUserFollowService.class),
                mock(NotificationPublisher.class),mock(IUserTagPreferenceService.class));
        assertEquals("dynamic",internal.forwardInfo(2L,9L).type());
        jdbc.update("UPDATE content SET visibility='private' WHERE id=2");
        assertThrows(BizException.class, () -> internal.forwardInfo(2L,9L));
        assertThrows(BizException.class, () -> internal.incrementShareCount(2L,9L));
        assertEquals(2L,internal.forwardInfo(2L,7L).contentId());
        assertTrue(internal.incrementShareCount(2L,7L));
        verify(counter).add(cn.diaojiahong.myyoutube.common.counter.CounterType.CONTENT_SHARE,2L,1);
        assertThrows(BizException.class, () -> internal.forwardInfo(5L,7L));
        assertThrows(BizException.class, () -> internal.forwardInfo(6L,7L));
        assertThrows(BizException.class, () -> internal.forwardInfo(7L,7L));
    }

    @Test void inaccessibleContentCannotCreateCollectionOrHistoryRecords() {
        var access = new ContentAccessService(contents,mock(AudioContentAccess.class));
        var counter = mock(CounterBuffer.class);
        var collections = new cn.diaojiahong.myyoutube.collect.service.ContentCollectServiceImpl(collects,summaries,access,
                mock(UserPort.class),mock(IUserFollowService.class),mock(S3StorageUtil.class),counter,mock(NotificationPublisher.class));
        var histories = new cn.diaojiahong.myyoutube.history.service.HistoryServiceImpl(history,summaries,access,
                mock(UserPort.class),mock(IUserFollowService.class),mock(S3StorageUtil.class));
        for (long id : List.of(4L,5L,6L,7L)) {
            assertThrows(BizException.class, () -> collections.collect(id));
            assertThrows(BizException.class, () -> histories.record(id));
        }
        verifyNoInteractions(counter);
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM content_collect",Integer.class));
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM user_view_history",Integer.class));
    }

    @Test void emptyPageAfterLastPageKeepsTheFilteredTotal() {
        var access = new ContentAccessService(contents,mock(AudioContentAccess.class));
        var collections = new cn.diaojiahong.myyoutube.collect.service.ContentCollectServiceImpl(collects,summaries,access,
                mock(UserPort.class),mock(IUserFollowService.class),mock(S3StorageUtil.class),mock(CounterBuffer.class),mock(NotificationPublisher.class));
        var query = new ContentPageQuery(); query.setType("dynamic"); query.setPageSize(2); query.setPageNo(3);
        var result = collections.page(query);
        assertEquals(3,result.getTotal()); assertEquals(2,result.getPages()); assertTrue(result.getList().isEmpty());
    }

    @Test void notificationsUseContentReferencesAndOnlyOfferCurrentlyReadableTargets() {
        jdbc.update("INSERT INTO user_notification(id,receiver_id,type,ref_type,ref_id,is_read) VALUES(1,7,'CONTENT_COMMENT','CONTENT',2,0),(2,7,'COMMENT_REPLY','COMMENT',101,0),(3,7,'CONTENT_COLLECT','CONTENT',3,0),(4,7,'SYSTEM_NOTICE','CONTENT',1,0)");
        when(comments.getById(101L)).thenReturn(new ContentComment().setId(101L).setContentId(4L).setStatus("visible"));
        var first = notifications.getNotificationList(new cn.diaojiahong.myyoutube.common.page.PageQuery()).getList()
                .stream().collect(Collectors.toMap(n -> n.getId(),n -> n));
        assertEquals(2L,first.get(1L).getContentId()); assertEquals("dynamic",first.get(1L).getMediaType());
        assertEquals("CONTENT",first.get(1L).getRefType());
        assertNull(first.get(2L).getContentId()); assertEquals("COMMENT",first.get(2L).getRefType());
        assertEquals(3L,first.get(3L).getContentId()); assertEquals("video",first.get(4L).getMediaType());
        jdbc.update("UPDATE content SET status='banned' WHERE id=2");
        var updated = notifications.getNotificationList(new cn.diaojiahong.myyoutube.common.page.PageQuery()).getList();
        assertEquals(4,updated.size());
        assertNull(updated.stream().filter(n -> n.getId()==1L).findFirst().orElseThrow().getContentId());
    }

    @Test void commentListenerEmitsDistinctContentAndCommentReferences() {
        var publisher = mock(NotificationPublisher.class);
        var listener = new cn.diaojiahong.myyoutube.mq.listener.PublishCommentListener(publisher);
        listener.onMessage(cn.diaojiahong.myyoutube.mq.message.CommentMessage.builder()
                .contentId(2L).commentId(101L).isContentComment(true).build());
        listener.onMessage(cn.diaojiahong.myyoutube.mq.message.CommentMessage.builder()
                .contentId(2L).commentId(101L).isContentComment(false).build());
        var notifications = org.mockito.ArgumentCaptor.forClass(cn.diaojiahong.myyoutube.notification.domain.po.UserNotification.class);
        verify(publisher,times(2)).publish(notifications.capture());
        var content = notifications.getAllValues().get(0); var reply = notifications.getAllValues().get(1);
        assertEquals("CONTENT_COMMENT",content.getType()); assertEquals("CONTENT",content.getRefType()); assertEquals(2L,content.getRefId());
        assertEquals("COMMENT_REPLY",reply.getType()); assertEquals("COMMENT",reply.getRefType()); assertEquals(101L,reply.getRefId());
    }
}
