package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.security.chain.ChainStep;

import cn.diaojiahong.myyoutube.security.AuthConstants;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 连接票步: 长连接(聊天 WS / SSE 门铃)优先消费 ?ticket= 一次性连接票
 * (chat 的 /chat/push/ticket 端点以 Bearer 换取, 票签发时已过 Bearer 鉴权)。
 * GETDEL 原子消费保证一票一用; 票不存在/已被消费按拒绝处理。
 */
@Component
public class TicketAuthStep implements ChainStep<AuthContext> {

    private static final String TICKET_QUERY_PARAM = "ticket";

    private final ReactiveStringRedisTemplate redisTemplate;

    public TicketAuthStep(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String group() {
        return GatewayAuthChain.GROUP;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Mono<Void> apply(AuthContext ctx) {
        if (!ctx.isConnectionPath()) {
            return Mono.empty();
        }
        String ticket = ctx.getExchange().getRequest().getQueryParams().getFirst(TICKET_QUERY_PARAM);
        if (ticket == null || ticket.isBlank()) {
            return Mono.empty();
        }
        return redisTemplate.opsForValue().getAndDelete(AuthConstants.PUSH_TICKET_KEY_PREFIX + ticket)
                .defaultIfEmpty("")
                .doOnNext(userId -> {
                    if (userId.isEmpty()) {
                        ctx.reject();
                    } else {
                        ctx.authenticated(Long.parseLong(userId));
                    }
                })
                .then();
    }
}
