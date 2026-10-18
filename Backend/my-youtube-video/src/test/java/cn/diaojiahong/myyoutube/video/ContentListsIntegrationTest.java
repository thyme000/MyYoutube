package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.collect.mapper.ContentCollectMapper;
import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
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

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
            return new UserNotificationServiceImpl(new ContentAccessService(mapper),mock(UserPort.class),comments);
        }
    }
    @Autowired DataSource ds;
    @Autowired ContentMapper contents;
    @Autowired IContentService contentService;
    @Autowired ContentCollectMapper collects;
    @Autowired UserViewHistoryMapper history;
    @Autowired ContentLikeMapper likes;
    @Autowired IContentCommentService comments;
    @Autowired UserNotificationServiceImpl notifications;
    JdbcTemplate jdbc;
    ContentSummaryPortImpl summaries;
    ContentFeedServiceImpl feeds;

    @BeforeEach void setup() {
        UserContext.setUser("7"); jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE content(id BIGINT PRIMARY KEY,type VARCHAR(16),author_id BIGINT,title VARCHAR(100),description TEXT,cover_object_key VARCHAR(512),category_code VARCHAR(32),tags TEXT,allow_comment INT,region VARCHAR(32),is_delete INT DEFAULT 0,status VARCHAR(16),visibility VARCHAR(16),play_count BIGINT DEFAULT 0,like_count BIGINT DEFAULT 0,collect_count BIGINT DEFAULT 0,comment_count BIGINT DEFAULT 0,share_count BIGINT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
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
        var registry = new ContentTypeStrategyRegistry(Arrays.stream(ContentTypeEnum.values()).map(type -> {
            var strategy = mock(ContentTypeStrategy.class); when(strategy.type()).thenReturn(type);
            when(strategy.loadMedia(anyList())).thenAnswer(call -> call.<List<Long>>getArgument(0).stream()
                    .collect(Collectors.toMap(id -> id, id -> type==ContentTypeEnum.VIDEO
                            ? new ContentMedia("video",new VideoMedia("video"+id,9000),null)
                            : new ContentMedia("dynamic",null,new DynamicMedia("vertical",1080,1920,"cut",0,
                            List.of(new DynamicMedia.Image("cover"+id,0,3000,0.5,0.5)),null,3000)))));
            return strategy;
        }).toList());
        summaries = new ContentSummaryPortImpl(contents,registry);
        feeds = new ContentFeedServiceImpl(contentService,likes,mock(IUserFollowService.class),mock(UserPort.class),
                mock(S3StorageUtil.class),mock(RecommendPort.class),registry);
    }

    @AfterEach void clear() { UserContext.removeUser(); }

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
        assertEquals(2,feeds.byAuthor(7L,7L,query).getTotal());
        assertEquals(1,feeds.byAuthor(9L,7L,query).getTotal());
        query.setType("VIDEO"); assertEquals(1,feeds.byAuthor(9L,7L,query).getTotal());
        assertEquals("video",query.getType());
        query.setType("note"); assertThrows(BizException.class, () -> feeds.mine(7L,query));
        query.setType(null); query.setPageSize(0); assertThrows(BizException.class, () -> feeds.mine(7L,query));
        query.setPageSize(100); query.setPageNo(Integer.MAX_VALUE); assertThrows(BizException.class, () -> feeds.mine(7L,query));
    }

    @Test void forwardingAndShareCounterRecheckCurrentStateWithAuthenticatedViewer() {
        var counter = mock(CounterBuffer.class);
        var internal = new InternalVideoController(mock(IContentTagService.class),contentService,
                mock(cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper.class),likes,
                new ContentAccessService(contents),counter,mock(IUserFollowService.class),
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
        var access = new ContentAccessService(contents);
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
        var access = new ContentAccessService(contents);
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
