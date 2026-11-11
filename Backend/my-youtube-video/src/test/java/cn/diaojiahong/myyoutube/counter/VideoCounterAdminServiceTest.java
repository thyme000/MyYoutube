package cn.diaojiahong.myyoutube.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.impl.ContentServiceImpl;
import cn.diaojiahong.myyoutube.like.counter.AuthorLikeCountService;
import cn.diaojiahong.myyoutube.like.counter.ContentLikeCounterTarget;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.share.counter.ContentShareCounterTarget;
import cn.diaojiahong.myyoutube.share.mapper.ContentShareMapper;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.mapper.CounterDriftMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(VideoCounterAdminServiceTest.Config.class)
class VideoCounterAdminServiceTest {

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {
            CounterDriftMapper.class,
            ContentMapper.class,
            ContentLikeMapper.class,
            ContentShareMapper.class
    })
    @Import({
            VideoCounterAdminService.class,
            CounterFlushService.class,
            ContentServiceImpl.class,
            AuthorLikeCountService.class,
            ContentLikeCounterTarget.class,
            ContentShareCounterTarget.class
    })
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:counter-drift-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*.xml"));
            return factory.getObject();
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        CounterBuffer counterBuffer() {
            return mock(CounterBuffer.class);
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
    private VideoCounterAdminService counters;

    @Autowired
    private UserPort users;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        jdbc.execute("DROP TABLE IF EXISTS author_like_count");
        jdbc.execute("DROP TABLE IF EXISTS content_like");
        jdbc.execute("DROP TABLE IF EXISTS content_comment_like");
        jdbc.execute("DROP TABLE IF EXISTS content_comment");
        jdbc.execute("DROP TABLE IF EXISTS content_collect");
        jdbc.execute("DROP TABLE IF EXISTS content_share");
        jdbc.execute("DROP TABLE IF EXISTS content");
        jdbc.execute("""
                CREATE TABLE content(
                    id BIGINT PRIMARY KEY,
                    author_id BIGINT,
                    like_count BIGINT DEFAULT 0,
                    collect_count BIGINT DEFAULT 0,
                    share_count BIGINT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE content_like(
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT,
                    content_id BIGINT,
                    is_liked INT
                )
                """);
        jdbc.execute("""
                CREATE TABLE content_comment(
                    id BIGINT PRIMARY KEY,
                    like_count INT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE content_comment_like(
                    id BIGINT PRIMARY KEY,
                    comment_id BIGINT,
                    is_liked INT
                )
                """);
        jdbc.execute("""
                CREATE TABLE content_collect(
                    id BIGINT PRIMARY KEY,
                    content_id BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE content_share(
                    id BIGINT PRIMARY KEY,
                    content_id BIGINT
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
        jdbc.update("INSERT INTO content(id, author_id, like_count, collect_count, share_count) VALUES (10, 7, 1, 0, 0), (11, 7, 5, 2, 9), (12, 7, 0, 0, 0), (13, 8, 0, 0, 0)");
        jdbc.update("INSERT INTO content_like(id, user_id, content_id, is_liked) VALUES (1, 1, 10, 1), (2, 2, 11, 1), (3, 3, 11, 0), (4, 4, 13, 1)");
        jdbc.update("INSERT INTO content_comment(id, like_count) VALUES (1, 0), (2, 3)");
        jdbc.update("INSERT INTO content_comment_like(id, comment_id, is_liked) VALUES (1, 2, 1)");
        jdbc.update("INSERT INTO content_collect(id, content_id) VALUES (1, 11)");
        jdbc.update("INSERT INTO content_share(id, content_id) VALUES (1, 11)");
        jdbc.update("INSERT INTO author_like_count(author_id, like_count, version, synced_version) VALUES (7, 5, 1, 1)");
        doReturn(List.of(7L)).when(users).overwriteReceivedLikes(any());
    }

    @Test
    void listsOnlyMismatchedRowsInIdOrder() {
        CursorPage<CounterDrift, Long> first = counters.drifts(CounterType.CONTENT_LIKE, null, 1);
        assertEquals(List.of(new CounterDrift(11, 5, 1, 4)), first.items());
        assertEquals(11L, first.nextCursor());

        CursorPage<CounterDrift, Long> second = counters.drifts(CounterType.CONTENT_LIKE, first.nextCursor(), 1);
        assertEquals(List.of(new CounterDrift(13, 0, 1, -1)), second.items());
        assertNull(second.nextCursor());

        assertEquals(List.of(new CounterDrift(2, 3, 1, 2)), counters.drifts(CounterType.COMMENT_LIKE, null, 10).items());
        assertEquals(List.of(new CounterDrift(11, 2, 1, 1)), counters.drifts(CounterType.CONTENT_COLLECT, null, 10).items());
        assertEquals(List.of(new CounterDrift(11, 9, 1, 8)), counters.drifts(CounterType.CONTENT_SHARE, null, 10).items());
    }

    @Test
    void repairsContentLikeThroughExistingOverwriteAndCopiesAuthorTotal() {
        int repaired = counters.repair(CounterType.CONTENT_LIKE, List.of(11L, 11L));

        assertEquals(1, repaired);
        assertEquals(1L, jdbc.queryForObject("SELECT like_count FROM content WHERE id = 11", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT like_count FROM author_like_count WHERE author_id = 7", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM author_like_count WHERE author_id = 7", Long.class));
        assertEquals(List.of(new CounterDrift(13, 0, 1, -1)), counters.drifts(CounterType.CONTENT_LIKE, null, 10).items());
        verify(users).overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 1, 2)));
    }

    @Test
    void repairsShareCountEvenThoughHourlyReconcileSkipsIt() {
        int repaired = counters.repair(CounterType.CONTENT_SHARE, List.of(11L));

        assertEquals(1, repaired);
        assertEquals(1L, jdbc.queryForObject("SELECT share_count FROM content WHERE id = 11", Long.class));
        assertEquals(List.of(), counters.drifts(CounterType.CONTENT_SHARE, null, 10).items());
    }

    @Test
    void rejectsCounterTypeOwnedByUserService() {
        assertThrows(BizException.class, () -> counters.drifts(CounterType.USER_FANS, null, 10));
        assertThrows(BizException.class, () -> counters.repair(CounterType.USER_FANS, List.of(1L)));
    }

    @Test
    void rejectsInvalidPageAndCursor() {
        assertThrows(BizException.class, () -> counters.drifts(CounterType.CONTENT_LIKE, null, 0));
        assertThrows(BizException.class, () -> counters.drifts(CounterType.CONTENT_LIKE, -1L, 10));
    }
}
