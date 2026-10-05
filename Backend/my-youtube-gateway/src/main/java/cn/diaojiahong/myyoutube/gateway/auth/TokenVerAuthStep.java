package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.common.chain.ChainStep;

import cn.diaojiahong.myyoutube.security.AuthConstants;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 作废校验步: 签发时间早于 token-ver 时间戳的 access token 立即失效(改密/封号)。
 * 只在 Bearer 步验签通过后执行; 时间戳脏数据按拒绝处理(fail-closed), 不许 500。
 */
@Component
public class TokenVerAuthStep implements ChainStep<AuthContext> {

    private final ReactiveStringRedisTemplate redisTemplate;

    public TokenVerAuthStep(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String group() {
        return GatewayAuthChain.GROUP;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Mono<Void> apply(AuthContext ctx) {
        var payload = ctx.getJwtPayload();
        if (payload == null) {
            return Mono.empty();
        }
        return redisTemplate.opsForValue()
                .get(AuthConstants.TOKEN_VER_KEY_PREFIX + payload.userId())
                .defaultIfEmpty("")
                .doOnNext(ver -> {
                    if (ver.isEmpty()) {
                        ctx.authenticated(payload.userId());
                        return;
                    }
                    long revokedAt;
                    try {
                        revokedAt = Long.parseLong(ver.trim());
                    } catch (NumberFormatException e) {
                        // 作废时间戳被写坏: 无法证明凭证仍有效, 按已作废处理
                        ctx.reject();
                        return;
                    }
                    if (payload.issuedAtMillis() < revokedAt) {
                        ctx.reject();
                    } else {
                        ctx.authenticated(payload.userId());
                    }
                })
                .then();
    }
}
