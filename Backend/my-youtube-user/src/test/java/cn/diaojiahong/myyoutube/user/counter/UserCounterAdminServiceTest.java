package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.user.mapper.CounterDriftMapper;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(UserCounterAdminServiceTest.Config.class)
class UserCounterAdminServiceTest {

    @Configuration
    @MapperScan(basePackageClasses = CounterDriftMapper.class)
    @Import(UserCounterAdminService.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:user-counter-drift-" + UUID.randomUUID()
                    + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/CounterDriftMapper.xml"));
            return factory.getObject();
        }

        @Bean
        CounterFlushService counterFlushService() {
            return mock(CounterFlushService.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }
    }

    @Autowired
    private UserCounterAdminService counters;

    @Autowired
    private CounterFlushService flush;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void schema() {
        jdbc.execute("DROP TABLE IF EXISTS user_follow");
        jdbc.execute("DROP TABLE IF EXISTS `user`");
        jdbc.execute("""
                CREATE TABLE `user`(
                    id BIGINT PRIMARY KEY,
                    fans BIGINT DEFAULT 0,
                    attention_count BIGINT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE user_follow(
                    id BIGINT PRIMARY KEY,
                    follower_id BIGINT,
                    followee_id BIGINT,
                    is_deleted INT
                )
                """);
        jdbc.update("INSERT INTO `user`(id, fans, attention_count) VALUES (1, 1, 0), (2, 0, 4), (3, 0, 0)");
        jdbc.update("""
                INSERT INTO user_follow(id, follower_id, followee_id, is_deleted) VALUES
                (1, 9, 1, 0),
                (2, 8, 1, 1),
                (3, 9, 2, 0),
                (4, 2, 9, 0)
                """);
    }

    @Test
    void listsFansAndAttentionMismatchesWithoutCancelledFollows() {
        CursorPage<CounterDrift, Long> fans = counters.drifts(CounterType.USER_FANS, null, 10);
        assertEquals(List.of(new CounterDrift(2, 0, 1, -1)), fans.items());
        assertNull(fans.nextCursor());

        CursorPage<CounterDrift, Long> attentions = counters.drifts(CounterType.USER_ATTENTION, null, 10);
        assertEquals(List.of(new CounterDrift(2, 4, 1, 3)), attentions.items());
        assertNull(attentions.nextCursor());
    }

    @Test
    void repairForwardsOwnedTypesAndRejectsVideoTypes() {
        when(flush.repair(CounterType.USER_FANS, List.of(2L))).thenReturn(1);

        assertEquals(1, counters.repair(CounterType.USER_FANS, List.of(2L)));
        assertThrows(BizException.class, () -> counters.repair(CounterType.CONTENT_LIKE, List.of(2L)));
        verify(flush, times(1)).repair(CounterType.USER_FANS, List.of(2L));
    }
}
