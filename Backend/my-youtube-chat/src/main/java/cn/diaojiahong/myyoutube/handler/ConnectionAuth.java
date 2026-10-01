package cn.diaojiahong.myyoutube.handler;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.security.JwtPayload;
import cn.diaojiahong.myyoutube.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 长连接(WS/SSE)鉴权: query 携带的裸 access token → userId, 含 token-ver 撤销校验。
 */
@Component
@RequiredArgsConstructor
public class ConnectionAuth {

    private final StringRedisTemplate redisTemplate;

    public Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        JwtPayload payload = JwtUtil.verify(token.trim());
        if (payload == null) {
            return null;
        }
        String ver = redisTemplate.opsForValue().get(RedisConstants.USER_TOKEN_VER + payload.userId());
        if (ver != null && payload.issuedAtMillis() < Long.parseLong(ver)) {
            return null;
        }
        return payload.userId();
    }
}
