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
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关统一鉴权: 本地验签 access JWT(不查 token 表), 仅作废时间戳命中时查一次 Redis,
 * 通过后把 userId 透传给下游服务(X-User-Id 头), 下游服务只解析头;
 * 配置了内部密钥时附带 X-Internal-Token, 供下游拒绝绕网关直连的伪造请求。
 * 长连接(聊天 WS / SSE 门铃): 优先消费 ?ticket= 一次性连接票(chat 的 /chat/push/ticket 以 Bearer 换取),
 * 兼容旧 ?token= 走验签; 凭证验完即从 query 剥掉换头转发, 下游链路不出现明文凭证。
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String TOKEN_VER_KEY_PREFIX = "user:token-ver:";
    private static final String TOKEN_QUERY_PARAM = "token";
    private static final String TICKET_QUERY_PARAM = "ticket";
    private static final String TICKET_KEY_PREFIX = "push:ticket:";
    /** 长连接转发前必须从 query 剥掉的凭证参数 */
    private static final List<String> CONNECTION_CREDENTIAL_PARAMS = List.of(TOKEN_QUERY_PARAM, TICKET_QUERY_PARAM);

    /** 放行接口, 与各服务的公开接口保持一致 */
    private static final List<String> WHITELIST = List.of(
            "/login", "/register", "/forget-password", "/refresh", "/logout");

    /** 长连接(聊天 WS / SSE 门铃): token 在 query 上, 验签后剥掉换头转发 */
    private static final List<String> CONNECTION_PREFIXES = List.of("/ws/", "/sse/");

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String internalToken;

    public GatewayAuthFilter(ReactiveStringRedisTemplate redisTemplate,
                             @Value("${jwt.secret}") String jwtSecret,
                             @Value("${my-youtube.internal-token:}") String internalToken) {
        this.redisTemplate = redisTemplate;
        this.internalToken = internalToken;
        JwtUtil.init(jwtSecret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = normalizePath(exchange.getRequest().getPath().value());
        if (WHITELIST.stream().anyMatch(path::equals)) {
            return chain.filter(exchange);
        }
        boolean connectionPath = CONNECTION_PREFIXES.stream().anyMatch(path::startsWith);

        // 一次性连接票优先: 签发时已过 Bearer 鉴权, 票本身就是凭证, GETDEL 原子消费
        String ticket = connectionPath ? exchange.getRequest().getQueryParams().getFirst(TICKET_QUERY_PARAM) : null;
        if (ticket != null && !ticket.isBlank()) {
            return redisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket)
                    .defaultIfEmpty("")
                    .flatMap(userId -> {
                        if (userId.isEmpty()) {
                            return unauthorized(exchange);
                        }
                        ServerHttpRequest request = buildDownstreamRequest(exchange, userId, true);
                        return chain.filter(exchange.mutate().request(request).build());
                    });
        }

        String token = extractToken(exchange, connectionPath);
        if (token == null) {
            return unauthorized(exchange);
        }
        JwtPayload payload = JwtUtil.verify(token);
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
                    ServerHttpRequest request = buildDownstreamRequest(exchange, String.valueOf(payload.userId()), connectionPath);
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    /** 普通请求只认 Bearer; 长连接优先 Bearer, 兜底 query 上的 token */
    private String extractToken(ServerWebExchange exchange, boolean connectionPath) {
        String header = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        if (connectionPath) {
            return exchange.getRequest().getQueryParams().getFirst(TOKEN_QUERY_PARAM);
        }
        return null;
    }

    private ServerHttpRequest buildDownstreamRequest(ServerWebExchange exchange, String userId, boolean connectionPath) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .header(USER_ID_HEADER, userId);
        if (!internalToken.isBlank()) {
            // 下游 UserIdHeaderFilter 据此拒绝绕过网关直连时伪造的 X-User-Id
            builder.header(INTERNAL_TOKEN_HEADER, internalToken);
        }
        if (connectionPath) {
            builder.uri(stripConnectionParams(exchange.getRequest().getURI()));
        }
        return builder.build();
    }

    private static URI stripConnectionParams(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return uri;
        }
        String stripped = Arrays.stream(rawQuery.split("&"))
                .filter(p -> CONNECTION_CREDENTIAL_PARAMS.stream()
                        .noneMatch(n -> p.equals(n) || p.startsWith(n + "=")))
                .collect(Collectors.joining("&"));
        return UriComponentsBuilder.fromUri(uri)
                .replaceQuery(stripped.isEmpty() ? null : stripped)
                .build(true)
                .toUri();
    }

    /** 归一化去掉尾斜杠, 避免 /login/ 之类变体绕开白名单精确匹配落进 401 */
    private static String normalizePath(String path) {
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
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
