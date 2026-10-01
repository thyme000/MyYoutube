package cn.diaojiahong.myyoutube.gateway.filter;

import cn.diaojiahong.myyoutube.security.JwtPayload;
import cn.diaojiahong.myyoutube.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
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
 * 网关统一鉴权: 本地验签 access JWT(不查 token 表), 仅作废时间戳命中时查一次 Redis,
 * 通过后把 userId 透传给下游服务(X-User-Id 头), 下游服务只解析头。
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String TOKEN_VER_KEY_PREFIX = "user:token-ver:";

    /** 放行接口, 与各服务的公开接口保持一致 */
    private static final List<String> WHITELIST = List.of(
            "/login", "/register", "/forget-password", "/refresh", "/logout");

    /** 长连接(聊天 WS / SSE 门铃)握手带不了自定义 header, 走 query token 由下游 ConnectionAuth 自行验签 */
    private static final List<String> SELF_AUTH_PREFIXES = List.of("/ws/", "/sse/");

    private final ReactiveStringRedisTemplate redisTemplate;

    public GatewayAuthFilter(ReactiveStringRedisTemplate redisTemplate,
                             @Value("${jwt.secret}") String jwtSecret) {
        this.redisTemplate = redisTemplate;
        JwtUtil.init(jwtSecret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (WHITELIST.stream().anyMatch(path::equals)) {
            return chain.filter(exchange);
        }
        if (SELF_AUTH_PREFIXES.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String header = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        JwtPayload payload = JwtUtil.verify(header.substring(BEARER_PREFIX.length()));
        if (payload == null) {
            return unauthorized(exchange);
        }

        // 改密码/封号后签发时间早于作废时间戳的 token 立即失效
        return redisTemplate.opsForValue().get(TOKEN_VER_KEY_PREFIX + payload.userId())
                .defaultIfEmpty("")
                .flatMap(ver -> {
                    if (!ver.isEmpty() && payload.issuedAtMillis() < Long.parseLong(ver)) {
                        return unauthorized(exchange);
                    }
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .header(USER_ID_HEADER, String.valueOf(payload.userId()))
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
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
