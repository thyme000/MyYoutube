package cn.diaojiahong.myyoutube.security;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.jwt.JWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/** 可重复使用的单视频播放凭证；使用独立派生密钥，不能作为登录 JWT 使用。 */
@Component
public class PlaybackTokens {
    public static final String QUERY_PARAM = "playbackToken";
    public static final String INTERNAL_HEADER = "X-Playback-Token";
    private static final String AUDIENCE = "video-playback";
    private static final long CLOCK_SKEW_MILLIS = 30000;

    public enum Scope { VIEW, REVIEW }

    private final byte[] key;
    private final long ttlMillis;
    private final Clock clock;

    @Autowired
    public PlaybackTokens(@Value("${jwt.secret}") String secret,
                          @Value("${my-youtube.playback.ttl-seconds:21600}") long ttlSeconds) {
        this(secret, ttlSeconds, Clock.systemUTC());
    }

    PlaybackTokens(String secret, long ttlSeconds, Clock clock) {
        if (secret == null || secret.isBlank() || ttlSeconds <= 0) {
            throw new IllegalArgumentException("播放签名密钥和有效期必须配置正确");
        }
        key = DigestUtil.sha256(AUDIENCE + ":" + secret);
        ttlMillis = Duration.ofSeconds(ttlSeconds).toMillis();
        this.clock = clock;
    }

    public Grant issue(long userId, long contentId, long durationMs, Scope scope) {
        long issuedAt = clock.millis();
        // 至少覆盖一遍正常观看和一小时暂停；长时间离开后由详情请求重新授权。
        long lifetime = Math.max(ttlMillis, durationMs + Duration.ofHours(1).toMillis());
        long expiresAt = (issuedAt + lifetime) / 1000 * 1000;
        String token = JWT.create().setPayload("aud", AUDIENCE)
                .setPayload("scope", scope.name()).setPayload("viewer", userId).setPayload("content", contentId)
                .setPayload("issuedAtMillis", issuedAt)
                .setExpiresAt(new Date(expiresAt)).setKey(key).sign();
        return new Grant(userId, contentId, issuedAt, expiresAt, scope, token);
    }

    public Grant verify(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            JWT jwt = JWT.of(token).setKey(key);
            if (!"HS256".equals(jwt.getHeader("alg")) || !jwt.verify()
                    || !AUDIENCE.equals(jwt.getPayload("aud"))) return null;
            Scope scope = Scope.valueOf(jwt.getPayload("scope").toString());
            long userId = Long.parseLong(jwt.getPayload("viewer").toString());
            long contentId = Long.parseLong(jwt.getPayload("content").toString());
            long issuedAt = Long.parseLong(jwt.getPayload("issuedAtMillis").toString());
            long expiresAt = Long.parseLong(jwt.getPayload("exp").toString()) * 1000;
            if (userId <= 0 || contentId <= 0 || issuedAt > clock.millis() + CLOCK_SKEW_MILLIS
                    || expiresAt <= clock.millis() || expiresAt <= issuedAt) return null;
            return new Grant(userId, contentId, issuedAt, expiresAt, scope, token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Grant(long userId, long contentId, long issuedAtMillis, long expiresAt, Scope scope, String token) {}
}
