package cn.diaojiahong.myyoutube.security;

import cn.hutool.jwt.JWT;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * HS256 access token 签发/验签。共享密钥经 JwtSecretConfig(或网关过滤器)注入, 各服务必须一致。
 * payload 只放 userId + 自定义 ts(签发毫秒), ts 用于与 Redis token-ver 失效时间戳比对。
 */
public final class JwtUtil {

    public static final long ACCESS_TTL_HOURS = 2L;

    private static volatile byte[] secret;

    private JwtUtil() {
    }

    public static void init(String secretStr) {
        if (secretStr == null || secretStr.isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置(jwt.secret)");
        }

        secret = secretStr.getBytes(StandardCharsets.UTF_8);
    }

    public static String sign(long userId) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .setPayload("userId", String.valueOf(userId))
                .setPayload("ts", now)
                .setIssuedAt(new Date(now))
                .setExpiresAt(new Date(now + ACCESS_TTL_HOURS * 3600_000L))
                .setKey(secret())
                .sign();
    }

    /** 验签 + 过期校验, 失败返回 null */
    public static JwtPayload verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            JWT jwt = JWT.of(token).setKey(secret());
            if (!jwt.validate(0)) {
                return null;
            }
            Object userId = jwt.getPayload("userId");
            Object ts = jwt.getPayload("ts");
            if (userId == null || ts == null) {
                return null;
            }
            return new JwtPayload(
                    Long.parseLong(String.valueOf(userId)),
                    Long.parseLong(String.valueOf(ts)));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] secret() {
        byte[] s = secret;
        if (s == null) {
            throw new IllegalStateException("JwtUtil 未初始化");
        }

        return s;
    }
}
