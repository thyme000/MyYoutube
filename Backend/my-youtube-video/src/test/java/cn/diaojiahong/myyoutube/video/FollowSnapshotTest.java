package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.follow.service.impl.UserFollowServiceImpl;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FollowSnapshotTest {
    JdbcTemplate jdbc;
    SqlSession session;
    UserFollowServiceImpl follows;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:follows-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_follow(id BIGINT PRIMARY KEY, follower_id BIGINT, followee_id BIGINT,"
                + "is_deleted BOOLEAN, update_time TIMESTAMP)");
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        SqlSessionFactory factory = bean.getObject();
        factory.getConfiguration().addMapper(UserFollowMapper.class);
        session = factory.openSession(true);
        follows = new UserFollowServiceImpl(mock(UserPort.class), mock(S3StorageUtil.class),
                mock(RocketMQTemplate.class), mock(EsSyncPublisher.class));
        ReflectionTestUtils.setField(follows, "baseMapper", session.getMapper(UserFollowMapper.class));
    }

    @AfterEach
    void close() {
        session.close();
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void includesFollowsBeyondTwoHundredAndExcludesOtherUsersAndCancelledRelations() {
        for (long id = 1; id <= 205; id++) {
            jdbc.update("INSERT INTO user_follow VALUES (?,7,?,false,CURRENT_TIMESTAMP)", id, id + 1000);
        }
        jdbc.update("INSERT INTO user_follow VALUES (206,7,9001,true,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO user_follow VALUES (207,8,9002,false,CURRENT_TIMESTAMP)");
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
