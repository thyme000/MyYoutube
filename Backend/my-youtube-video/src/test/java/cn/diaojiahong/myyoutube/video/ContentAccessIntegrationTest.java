package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.comment.cache.CommentListCache;
import cn.diaojiahong.myyoutube.comment.domain.dto.*;
import cn.diaojiahong.myyoutube.comment.service.impl.ContentCommentServiceImpl;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.transcode.api.TranscodePort;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.service.PublicationEffectsService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ContentAccessIntegrationTest.Config.class)
class ContentAccessIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {ContentMapper.class, VideoMapper.class, cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper.class})
    static class Config {
        @Bean DataSource dataSource() { return ContentTestDatabase.create("content-access"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean=new MybatisSqlSessionFactoryBean();bean.setDataSource(ds);return bean.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TransactionTemplate transaction(DataSourceTransactionManager manager) { return spy(new TransactionTemplate(manager)); }
        @Bean IContentService contents() { return new ContentServiceImpl(); }
        @Bean ContentAccessService access(ContentMapper mapper) { return new ContentAccessService(mapper); }
        @Bean PublicationEffectsService effects() { return mock(PublicationEffectsService.class); }
        @Bean ContentSettingsService settings(ContentAccessService access, ContentMapper mapper, ContentPublicationEventMapper events, PublicationEffectsService effects) {
            return new ContentSettingsService(access,mapper,events,effects);
        }
        @Bean S3StorageUtil storage() { return mock(S3StorageUtil.class); }
        @Bean TranscodePort transcode() { return mock(TranscodePort.class); }
        @Bean ContentDownloadService downloads(ContentAccessService access, S3StorageUtil storage, TranscodePort transcode) {
            return new ContentDownloadService(access,mock(ContentTypeStrategyRegistry.class),transcode,storage);
        }
        @Bean ContentCommentServiceImpl comments(IContentService contents, ContentAccessService access, TransactionTemplate tx) {
            return new ContentCommentServiceImpl(mock(UserPort.class),mock(S3StorageUtil.class),contents,access,
                    mock(org.springframework.data.redis.core.StringRedisTemplate.class),mock(org.apache.rocketmq.spring.core.RocketMQTemplate.class),tx,mock(CommentListCache.class));
        }
    }
    @Autowired DataSource ds;
    @Autowired ContentAccessService access;
    @Autowired ContentSettingsService settings;
    @Autowired cn.diaojiahong.myyoutube.comment.service.IContentCommentService comments;
    @Autowired ContentDownloadService downloads;
    @Autowired TranscodePort transcode;
    @Autowired S3StorageUtil storage;
    @Autowired TransactionTemplate tx;
    JdbcTemplate jdbc;
    @BeforeEach void schema() {
        reset(tx,transcode,storage);
        UserContext.setUser("9"); jdbc=new JdbcTemplate(ds); ContentTestDatabase.drop(jdbc,"content","content_comment","content_publication_event");
        jdbc.execute("CREATE TABLE content(id BIGINT PRIMARY KEY,type VARCHAR(16),author_id BIGINT,title VARCHAR(100),description TEXT,cover_object_key VARCHAR(512),category_code VARCHAR(32),tags TEXT,allow_comment INT,region VARCHAR(32),is_delete INT DEFAULT 0,status VARCHAR(16),visibility VARCHAR(16),play_count BIGINT DEFAULT 0,like_count BIGINT DEFAULT 0,collect_count BIGINT DEFAULT 0,comment_count BIGINT DEFAULT 0,share_count BIGINT DEFAULT 0,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE content_comment(id BIGINT AUTO_INCREMENT PRIMARY KEY,content_id BIGINT,user_id BIGINT,path VARCHAR(256),level INT,reply_user_id BIGINT,content TEXT,like_count INT,reply_count INT,status VARCHAR(16),create_time TIMESTAMP,update_time TIMESTAMP)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("pipeline-schema.sql")).execute(ds);
        jdbc.update("INSERT INTO content(id,type,author_id,title,status,visibility,is_delete,allow_comment) VALUES(1,'dynamic',7,'标题','published','public',0,1)");
    }
    @AfterEach void clear() { UserContext.removeUser(); }
    CommentDTO comment(Long parent) { var dto=new CommentDTO();dto.setContentId(1L);dto.setContent("评论");dto.setParentId(parent);return dto; }
    @Test void closingCommentsPreservesExistingRowsAndBlocksBothNewCommentsAndReplies() {
        comments.publishComment(comment(null));
        long root=jdbc.queryForObject("SELECT id FROM content_comment",Long.class);
        settings.update(1L,7L,new ContentSettingsService.Settings("public",0));
        assertThrows(BizException.class, () -> comments.publishComment(comment(null)));
        assertThrows(BizException.class, () -> comments.publishComment(comment(root)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_comment",Integer.class));
        var query=new CommentReplyQuery(); query.setRootId(root);
        assertNotNull(comments.getReplies(query));
        assertEquals(1L,jdbc.queryForObject("SELECT comment_count FROM content",Long.class));
    }
    @Test void commentThatPassedInitialCheckIsRejectedWhenClosureCommitsBeforeWrite() throws Exception {
        var beforeWrite=new CountDownLatch(1);var resume=new CountDownLatch(1);
        doAnswer(call -> {
            beforeWrite.countDown();assertTrue(resume.await(5,TimeUnit.SECONDS));return call.callRealMethod();
        }).when(tx).executeWithoutResult(any());
        var submitted=CompletableFuture.runAsync(() -> {
            UserContext.setUser("9");try { comments.publishComment(comment(null)); } finally {UserContext.removeUser();}
        });
        try {
            assertTrue(beforeWrite.await(5,TimeUnit.SECONDS));
            settings.update(1L,7L,new ContentSettingsService.Settings("public",0));
        } finally { resume.countDown(); }
        assertInstanceOf(BizException.class,assertThrows(CompletionException.class,submitted::join).getCause());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_comment",Integer.class));
    }
    @Test void privateSettingsUseSharedPermissionAndDurableSearchOnlyEvent() {
        settings.update(1L,7L,new ContentSettingsService.Settings("private",0));
        settings.update(1L,7L,new ContentSettingsService.Settings("private",1));
        assertNotNull(access.readable(1L,7L));assertThrows(BizException.class, () -> access.readable(1L,9L));
        var query = new CommentListQuery(); query.setContentId(1L);
        assertThrows(BizException.class, () -> comments.getRootComments(query));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT user_count_done FROM content_publication_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT notice_done FROM content_publication_event",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT search_done FROM content_publication_event",Integer.class));
        assertThrows(BizException.class, () -> settings.update(1L,9L,new ContentSettingsService.Settings("public",1)));
        assertThrows(BizException.class, () -> settings.update(1L,7L,new ContentSettingsService.Settings("unlisted",1)));
    }
    @Test void completedDownloadRechecksCurrentAccessAndRequester() {
        when(transcode.getTask(20L)).thenReturn(TranscodeTaskVO.builder().taskId(20L).contentId(1L).userId(9L).status(TranscodeTaskStatus.DONE).resultObjectKey("export.mp4").build());
        when(storage.signGetUrl("export.mp4",1200L)).thenReturn("signed");
        assertEquals("signed",downloads.status(20L,9L).downloadUrl());
        assertThrows(BizException.class, () -> downloads.status(20L,10L));
        settings.update(1L,7L,new ContentSettingsService.Settings("private",1));
        assertThrows(BizException.class, () -> downloads.status(20L,9L));
        verify(storage,times(1)).signGetUrl("export.mp4",1200L);
    }
    @Test void deletedOrUnpublishedContentIsUnreadableEvenToAuthor() {
        jdbc.update("UPDATE content SET status='review'");assertThrows(BizException.class, () -> access.readable(1L,7L));
        jdbc.update("UPDATE content SET status='published'");settings.delete(1L,7L);
        assertThrows(BizException.class, () -> access.readable(1L,7L));
        assertEquals(-1,jdbc.queryForObject("SELECT delta FROM content_publication_event",Integer.class));
    }
}
