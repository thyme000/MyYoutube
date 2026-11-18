package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.content.report.PlaybackReportStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放会话发号实测(真 Redis): 固定 TTL 内后续上报加入同一会话。
 * 运行方式: -Dplayback.redis.port=6379 (本地 compose Redis)。
 */
@EnabledIfSystemProperty(named = "playback.redis.port", matches = "[0-9]+")
class LivePlaybackReportStoreTest {

    /** 每个用例使用隔离的测试账号，key 在 @AfterEach 清理。 */
    final long userId = ThreadLocalRandom.current().nextLong(987_000_000L, 988_000_000L);
    final String sessionKey = RedisConstants.PLAYBACK_REPORT_SESSION + userId;

    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    PlaybackReportStore store;

    @BeforeEach
    void setup() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration("127.0.0.1", Integer.getInteger("playback.redis.port"));
        // compose Redis 默认密码, 可用 -Dplayback.redis.password 覆盖
        configuration.setPassword(RedisPassword.of(System.getProperty("playback.redis.password", "123456")));
        connection = new LettuceConnectionFactory(configuration);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        store = new PlaybackReportStore(redis);
        redis.delete(sessionKey);
    }

    @AfterEach
    void cleanup() {
        redis.delete(sessionKey);
        connection.destroy();
    }

    @Test
    void sessionIsCreatedOnceAndJoinedWithinTtl() {
        String sessionId = store.sessionFor(userId);
        assertNotNull(sessionId);
        // 会话已写入且带 TTL
        assertEquals(sessionId, redis.opsForValue().get(sessionKey));
        assertTrue(redis.getExpire(sessionKey, TimeUnit.SECONDS) > 0);

        // TTL 内后续上报加入同一会话
        assertEquals(sessionId, store.sessionFor(userId));
        assertEquals(sessionId, store.sessionFor(userId));
    }
}
