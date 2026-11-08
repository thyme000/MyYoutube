package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.follow.service.impl.UserFollowServiceImpl;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.port.UserPortAdapter;
import cn.diaojiahong.myyoutube.user.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FollowSnapshotTest {
    JdbcTemplate jdbc;
    UserFollowServiceImpl follows;

    @BeforeEach
    void setup() throws Exception {
        // NON_KEYWORDS=USER: MP 生成的 UPDATE user 不带引号, H2 默认把 USER 当保留字(MySQL 不保留)
        var ds = new DriverManagerDataSource("jdbc:h2:mem:follows-" + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_follow(id BIGINT PRIMARY KEY, follower_id BIGINT, followee_id BIGINT,"
                + "is_deleted BOOLEAN, create_time TIMESTAMP, update_time TIMESTAMP,"
                + "UNIQUE(follower_id, followee_id))");
        // guardedDelta 直更 user.fans / user.attention_count, 测试只需这两列
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY, fans BIGINT DEFAULT 0,"
                + "attention_count BIGINT DEFAULT 0)");
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        // followOrRestore/cancelFollow 在 XML 里, 手工建的工厂必须显式挂上 mapperLocations
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/UserFollowMapper.xml"));
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().addMapper(UserFollowMapper.class);
        factory.getConfiguration().addMapper(UserMapper.class);
        // SqlSessionTemplate 让 mapper 语句真正加入 Spring 事务, 而不是各自自动提交
        var sessions = new SqlSessionTemplate(factory);
        var users = new UserServiceImpl(mock(S3StorageUtil.class), mock(EsSyncPublisher.class),
                mock(UserSummaryCache.class));
        ReflectionTestUtils.setField(users, "baseMapper", sessions.getMapper(UserMapper.class));
        follows = new UserFollowServiceImpl(users, mock(UserPortAdapter.class), mock(S3StorageUtil.class),
                mock(EsSyncPublisher.class), new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(follows, "baseMapper", sessions.getMapper(UserFollowMapper.class));
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    private long fansOf(long id) {
        return jdbc.queryForObject("SELECT fans FROM `user` WHERE id=?", Long.class, id);
    }

    private long attentionOf(long id) {
        return jdbc.queryForObject("SELECT attention_count FROM `user` WHERE id=?", Long.class, id);
    }

    @Test
    void followWritesRelationAndCountersInOneTransaction() {
        jdbc.update("INSERT INTO `user`(id) VALUES (7),(8)");
        UserContext.setUser("7");
        try {
            follows.follow(8L);

            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_follow WHERE follower_id=7 AND followee_id=8 AND is_deleted=FALSE",
                    Integer.class));
            assertEquals(1L, fansOf(8L));
            assertEquals(1L, attentionOf(7L));

            // 重复关注: 幂等靠 MySQL "无变更 UPDATE 影响行数=0" 契约(写侧计数不重复累加),
            // H2 对无变更也报 2 行无法模拟, 这里只钉去重后的单行事实
            follows.follow(8L);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_follow WHERE follower_id=7 AND followee_id=8", Integer.class));
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void cancelFollowDecrementsCountersAndNeverGoesNegative() {
        jdbc.update("INSERT INTO user_follow VALUES (1,7,8,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        // fans 预置为 0 模拟计数列已漂移的场景, 护底不许减成负数
        jdbc.update("INSERT INTO `user`(id) VALUES (7),(8)");
        UserContext.setUser("7");
        try {
            follows.cancelFollow(8L);

            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_follow WHERE follower_id=7 AND followee_id=8 AND is_deleted=TRUE",
                    Integer.class));
            assertEquals(0L, fansOf(8L));
            assertEquals(0L, attentionOf(7L));

            // 未关注再取关: 影响行数=0, 计数不动
            follows.cancelFollow(8L);
            assertEquals(0L, fansOf(8L));
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void removeFanEndsTheFollowerRelationshipAndItsCounters() {
        jdbc.update("INSERT INTO user_follow VALUES (1,8,7,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO `user`(id) VALUES (7),(8)");
        UserContext.setUser("7");
        try {
            follows.removeFan(8L);

            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_follow WHERE follower_id=8 AND followee_id=7 AND is_deleted=TRUE",
                    Integer.class));
            assertEquals(0L, fansOf(7L));
            assertEquals(0L, attentionOf(8L));
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void includesFollowsBeyondTwoHundredAndExcludesOtherUsersAndCancelledRelations() {
        for (long id = 1; id <= 205; id++) {
            jdbc.update("INSERT INTO user_follow VALUES (?,7,?,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, id + 1000);
        }
        jdbc.update("INSERT INTO user_follow VALUES (206,7,9001,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO user_follow VALUES (207,8,9002,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        List<Long> ids = follows.getFollowIds(7L);
        assertEquals(205, ids.size());
        for (long id = 1001; id <= 1205; id++) assertTrue(ids.contains(id));
        assertFalse(ids.contains(9001L));
        assertFalse(ids.contains(9002L));
    }

    @Test
    void userWithoutFollowsHasAnEmptySnapshot() {
        assertEquals(List.of(), follows.getFollowIds(7L));
    }
}
