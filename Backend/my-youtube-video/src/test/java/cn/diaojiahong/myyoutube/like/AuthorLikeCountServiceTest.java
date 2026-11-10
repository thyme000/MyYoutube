package cn.diaojiahong.myyoutube.like;

import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.like.counter.AuthorLikeCountService;
import cn.diaojiahong.myyoutube.like.mapper.AuthorLikeCountMapper;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.ReceivedLikeUpdate;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(AuthorLikeCountServiceTest.Config.class)
class AuthorLikeCountServiceTest {

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {ContentMapper.class, AuthorLikeCountMapper.class})
    @Import({AuthorLikeCountService.class, ContentServiceImpl.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:author-like-count;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/AuthorLikeCountMapper.xml"));
            return factory.getObject();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        UserPort userPort() {
            return mock(UserPort.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }
    }

    @Autowired
    private AuthorLikeCountService authorLikes;

    @Autowired
    private UserPort users;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        jdbc.execute("DROP TABLE IF EXISTS author_like_count");
        jdbc.execute("DROP TABLE IF EXISTS content");
        jdbc.execute("""
                CREATE TABLE content(
                    id BIGINT PRIMARY KEY,
                    author_id BIGINT,
                    like_count BIGINT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE author_like_count(
                    author_id BIGINT PRIMARY KEY,
                    like_count BIGINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    synced_version BIGINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.update("INSERT INTO content(id, author_id, like_count) VALUES (10, 7, 0), (11, 7, 0)");
    }

    @Test
    void contentDeltaAndReconcileShareOneAuthorTotal() {
        authorLikes.apply(10, 2);
        authorLikes.apply(11, 4);
        assertEquals(2L, likeCount(10));
        assertEquals(4L, likeCount(11));
        assertEquals(6L, authorCount());
        assertEquals(2L, authorVersion());

        authorLikes.apply(10, -1);
        assertEquals(1L, likeCount(10));
        assertEquals(5L, authorCount());

        authorLikes.apply(10, -5);
        assertEquals(1L, likeCount(10));
        assertEquals(5L, authorCount());
        assertEquals(3L, authorVersion());

        authorLikes.overwrite(11, 1);
        assertEquals(1L, likeCount(11));
        assertEquals(2L, authorCount());
        assertEquals(4L, authorVersion());

        authorLikes.overwrite(11, 1);
        assertEquals(4L, authorVersion());
    }

    @Test
    void failedCopyStaysPendingAndAMovedVersionIsNotMarkedSynced() {
        authorLikes.apply(10, 3);
        doReturn(List.of()).when(users).overwriteReceivedLikes(any());
        authorLikes.pushPending();
        assertEquals(0L, syncedVersion());

        doThrow(new IllegalStateException("user down")).when(users).overwriteReceivedLikes(any());
        authorLikes.pushPending();
        assertEquals(0L, syncedVersion());

        doAnswer(invocation -> {
            List<ReceivedLikeUpdate> updates = invocation.getArgument(0);
            assertEquals(1, updates.size());
            ReceivedLikeUpdate update = updates.get(0);
            assertEquals(3L, update.likeCount());
            assertEquals(1L, update.version());
            jdbc.update("UPDATE author_like_count SET like_count = like_count + 1, version = version + 1 WHERE author_id = 7");
            return List.of(7L);
        }).when(users).overwriteReceivedLikes(any());
        authorLikes.pushPending();
        assertEquals(0L, syncedVersion());
        assertEquals(2L, authorVersion());

        doReturn(List.of(7L)).when(users).overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 4, 2)));
        authorLikes.pushPending();
        assertEquals(2L, syncedVersion());
    }

    private long likeCount(long contentId) {
        return jdbc.queryForObject("SELECT like_count FROM content WHERE id = ?", Long.class, contentId);
    }

    private long authorCount() {
        return jdbc.queryForObject("SELECT like_count FROM author_like_count WHERE author_id = 7", Long.class);
    }

    private long authorVersion() {
        return jdbc.queryForObject("SELECT version FROM author_like_count WHERE author_id = 7", Long.class);
    }

    private long syncedVersion() {
        return jdbc.queryForObject("SELECT synced_version FROM author_like_count WHERE author_id = 7", Long.class);
    }
}
