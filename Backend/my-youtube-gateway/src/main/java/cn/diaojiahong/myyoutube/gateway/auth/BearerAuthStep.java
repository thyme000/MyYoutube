package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.security.JwtPayload;
import cn.diaojiahong.myyoutube.security.JwtUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Bearer 验签步: 本地验签 access JWT(不查 token 表), 通过后载荷暂存上下文,
 * 作废校验交给 TokenVerAuthStep。无 Bearer 头则交给下一步(最终无凭证统一 401)。
 */
@Order(20)
@Component
public class BearerAuthStep implements AuthStep {

    @Override
    public Mono<Void> apply(AuthContext ctx) {
        String header = ctx.getExchange().getRequest().getHeaders().getFirst(AuthConstants.AUTH_HEADER);
        if (header == null || !header.startsWith(AuthConstants.BEARER_PREFIX)) {
            return Mono.empty();
        }
        String token = header.substring(AuthConstants.BEARER_PREFIX.length());
        JwtPayload payload = JwtUtil.verify(token);
        if (payload == null) {
            ctx.reject();
            return Mono.empty();
        }
        ctx.jwtPayload(payload);
        return Mono.empty();
    }
}
