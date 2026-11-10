package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.user.api.dto.ReceivedLikeUpdate;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.port.UserPortAdapter;
import cn.diaojiahong.myyoutube.user.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ReceivedLikeSyncTest {

    private final UserSummaryCache cache = mock(UserSummaryCache.class);
    private final EsSyncPublisher publisher = mock(EsSyncPublisher.class);
    private JdbcTemplate jdbc;
    private SqlSession session;
    private UserPortAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:received-like;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS user");
        jdbc.execute("""
                CREATE TABLE user (
                    id BIGINT PRIMARY KEY, username VARCHAR(64), password VARCHAR(128),
                    role VARCHAR(16) NOT NULL DEFAULT 'USER',
                    avatar_url VARCHAR(255), email VARCHAR(128), phone VARCHAR(32),
                    nickname VARCHAR(64), create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
                    is_delete INT DEFAULT 0, fans BIGINT DEFAULT 0, attention_count BIGINT DEFAULT 0,
                    introduction VARCHAR(255), bg_image VARCHAR(255),
                    like_count BIGINT NOT NULL DEFAULT 0, like_version BIGINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.update("INSERT INTO user(id, create_time) VALUES (7, CURRENT_TIMESTAMP)");

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(UserMapper.class);
        session = factory.openSession(true);
        UserServiceImpl users = new UserServiceImpl(mock(S3StorageUtil.class), publisher, cache);
        ReflectionTestUtils.setField(users, "baseMapper", session.getMapper(UserMapper.class));
        adapter = new UserPortAdapter(users, cache, publisher);
    }

    @AfterEach
    void cleanup() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void olderVersionDoesNotOverwriteAndTheSameVersionRepublishes() {
        assertEquals(List.of(7L), adapter.overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 4, 2))));
        assertEquals(4L, likeCount(7));
        assertEquals(2L, likeVersion(7));
        verify(cache).evict(7L);
        verify(publisher).syncUserDoc(7L);

        assertEquals(List.of(7L), adapter.overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 1, 1))));
        assertEquals(4L, likeCount(7));
        assertEquals(2L, likeVersion(7));
        verify(cache, times(1)).evict(7L);
        verify(publisher, times(1)).syncUserDoc(7L);

        assertEquals(List.of(7L), adapter.overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 4, 2))));
        verify(cache, times(2)).evict(7L);
        verify(publisher, times(2)).syncUserDoc(7L);

        assertEquals(List.of(7L), adapter.overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 9, 3))));
        assertEquals(9L, likeCount(7));
        assertEquals(3L, likeVersion(7));
        verify(publisher, times(3)).syncUserDoc(7L);
    }

    @Test
    void missingUserIsSkippedAndDoesNotBlockSiblings() {
        jdbc.update("INSERT INTO user(id, create_time) VALUES (9, CURRENT_TIMESTAMP)");

        List<Long> accepted = adapter.overwriteReceivedLikes(List.of(
                new ReceivedLikeUpdate(8, 1, 1),
                new ReceivedLikeUpdate(9, 5, 2)));

        assertEquals(List.of(9L), accepted);
        assertEquals(5L, likeCount(9));
        assertEquals(2L, likeVersion(9));
        verify(cache).evict(9L);
        verify(publisher).syncUserDoc(9L);
        verifyNoInteractionsOnUser(8);
    }

    private void verifyNoInteractionsOnUser(long userId) {
        verify(cache, times(0)).evict(userId);
        verify(publisher, times(0)).syncUserDoc(userId);
    }

    private long likeCount(long userId) {
        return jdbc.queryForObject("SELECT like_count FROM user WHERE id = ?", Long.class, userId);
    }

    private long likeVersion(long userId) {
        return jdbc.queryForObject("SELECT like_version FROM user WHERE id = ?", Long.class, userId);
    }
}
