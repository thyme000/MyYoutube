package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.recommend.mapper.UserTagPreferenceMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTagPreferenceIntegrationTest {
    private JdbcTemplate jdbc;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = ContentTestDatabase.create("tag-preference");
        jdbc = new JdbcTemplate(source);
        ContentTestDatabase.drop(jdbc, "user_tag_preference", "content_tag");
        jdbc.execute("""
                CREATE TABLE content_tag (
                    content_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, UNIQUE (content_id, tag_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE user_tag_preference (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, tag_id BIGINT NOT NULL,
                    score INT NOT NULL, source VARCHAR(16) NOT NULL DEFAULT 'behavior', UNIQUE (user_id, tag_id)
                )
                """);
        jdbc.update("INSERT INTO content_tag (content_id, tag_id) VALUES (1, 20), (1, 30), (2, 40)");

        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setMapperLocations(new ClassPathResource("mapper/UserTagPreferenceMapper.xml"));
        factory = bean.getObject();
    }

    @Test
    void addsSignedDeltasOnlyForTheContentsTagsAndPreservesExistingSource() {
        jdbc.update("INSERT INTO user_tag_preference (user_id, tag_id, score, source) VALUES (9, 20, 5, 'manual')");
        addScore(1L, -2);
        addScore(3L, 4);

        assertEquals(List.of(3, -2), scores());
        assertEquals(List.of("manual", "behavior"),
                jdbc.queryForList("SELECT source FROM user_tag_preference WHERE user_id=9 ORDER BY tag_id", String.class));
    }

    @Test
    void concurrentReportsAccumulateBothDeltas() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS));
                addScore(1L, 2);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS));
                addScore(1L, 4);
                return null;
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of(6, 6), scores());
    }

    private void addScore(Long contentId, int delta) {
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(UserTagPreferenceMapper.class).addBehaviorScore(9L, contentId, delta);
        }
    }

    private List<Integer> scores() {
        return jdbc.queryForList("SELECT score FROM user_tag_preference WHERE user_id=9 ORDER BY tag_id", Integer.class);
    }
}
