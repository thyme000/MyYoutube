package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.user.api.dto.ContentCountEvent;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.mapper.UserCounterEventMapper;
import cn.diaojiahong.myyoutube.user.port.UserPortAdapter;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ContentCountEventIntegrationTest.Config.class)
class ContentCountEventIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = UserCounterEventMapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            if (!Boolean.getBoolean("content.integration"))
                return new DriverManagerDataSource("jdbc:h2:mem:counters;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
            var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/content_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", "content-it-only");
            if (new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM isolated_test_environment WHERE id=1", Integer.class) != 1)
                throw new IllegalStateException("Missing isolated Docker environment marker");
            return ds;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new SqlSessionFactoryBean(); bean.setDataSource(ds); return bean.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean UserPortAdapter adapter(UserCounterEventMapper mapper) {
            return new UserPortAdapter(mock(IUserService.class), mapper, mock(CounterBuffer.class), mock(UserSummaryCache.class));
        }
    }
    @Autowired cn.diaojiahong.myyoutube.user.api.UserPort adapter;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;
    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS user_counter_event"); jdbc.execute("DROP TABLE IF EXISTS user");
        jdbc.execute("CREATE TABLE user_counter_event(event_id VARCHAR(80) PRIMARY KEY, user_id BIGINT, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE user(id BIGINT PRIMARY KEY, content_count BIGINT NOT NULL DEFAULT 0)");
        jdbc.update("INSERT INTO user(id) VALUES(1)");
    }
    @Test void duplicateDeliveryAndOrderedBanOnlyChangeCounterOnce() {
        var publish = new ContentCountEvent("publish", 1L, 1);
        adapter.applyContentCountEvent(publish); adapter.applyContentCountEvent(publish);
        assertEquals(1L, jdbc.queryForObject("SELECT content_count FROM user WHERE id=1", Long.class));
        var ban = new ContentCountEvent("ban", 1L, -1);
        adapter.applyContentCountEvent(ban); adapter.applyContentCountEvent(ban);
        assertEquals(0L, jdbc.queryForObject("SELECT content_count FROM user WHERE id=1", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_counter_event", Integer.class));
    }
    @Test void rejectedDeltaRollsBackLedgerSoRetryCanSucceed() {
        var event = new ContentCountEvent("late-ban", 1L, -1);
        assertThrows(IllegalStateException.class, () -> adapter.applyContentCountEvent(event));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_counter_event", Integer.class));
        jdbc.update("UPDATE user SET content_count=1 WHERE id=1");
        adapter.applyContentCountEvent(event);
        assertEquals(0L, jdbc.queryForObject("SELECT content_count FROM user WHERE id=1", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_counter_event", Integer.class));
    }
    @Test void genericCounterEndpointCannotBypassVideoEventDeduplication() {
        assertThrows(IllegalArgumentException.class, () -> adapter.adjustCounters(java.util.List.of(
                new cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust(1L, "contentCount", 1))));
        assertEquals(0L, jdbc.queryForObject("SELECT content_count FROM user WHERE id=1", Long.class));
    }

}
