package cn.diaojiahong.myyoutube.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关统一鉴权: 校验 token 后把 userId 透传给下游服务(X-User-Id 头),
 * 下游服务只解析头, 不再各自查 Redis。
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final String TOKEN_HEADER = "Authorization";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String TOKEN_KEY_PREFIX = "user:token:";

    /** 放行接口, 与各服务的公开接口保持一致 */
    private static final List<String> WHITELIST = List.of(
            "/login", "/register", "/forget-password");

    private final ReactiveStringRedisTemplate redisTemplate;

    public GatewayAuthFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (WHITELIST.stream().anyMatch(path::equals)) {
            return chain.filter(exchange);
        }

        String token = exchange.getRequest().getHeaders().getFirst(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return unauthorized(exchange);
        }
        return redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token)
                .flatMap(userId -> {
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .header(USER_ID_HEADER, userId)
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .switchIfEmpty(unauthorized(exchange));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
