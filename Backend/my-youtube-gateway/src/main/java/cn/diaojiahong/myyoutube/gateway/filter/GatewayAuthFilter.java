package cn.diaojiahong.myyoutube.gateway.filter;

import cn.diaojiahong.myyoutube.security.AuthConstants;
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
 * 其余凭证只认 Bearer 头; 凭证验完即从 query 剥掉换头转发, 下游链路不出现明文凭证。
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    // 头名/Redis key 前缀与 common 的 UserIdHeaderFilter、RedisConstants 同源, 单点在 security.AuthConstants
    private static final String AUTH_HEADER = AuthConstants.AUTH_HEADER;
    private static final String BEARER_PREFIX = AuthConstants.BEARER_PREFIX;
    private static final String USER_ID_HEADER = AuthConstants.USER_ID_HEADER;
    private static final String INTERNAL_TOKEN_HEADER = AuthConstants.INTERNAL_TOKEN_HEADER;
    private static final String TOKEN_VER_KEY_PREFIX = AuthConstants.TOKEN_VER_KEY_PREFIX;
    private static final String TICKET_KEY_PREFIX = AuthConstants.PUSH_TICKET_KEY_PREFIX;
    private static final String TICKET_QUERY_PARAM = "ticket";
    /** 长连接转发前必须从 query 剥掉的凭证参数(token 兜底防旧客户端把凭证塞 query) */
    private static final List<String> CONNECTION_CREDENTIAL_PARAMS = List.of("token", TICKET_QUERY_PARAM);

    /** 长连接(聊天 WS / SSE 门铃): token 在 query 上, 验签后剥掉换头转发 */
    private static final List<String> CONNECTION_PREFIXES = List.of("/ws/", "/sse/");

    /** 放行接口由配置驱动(与路由 yml 的公开路径职责不同: 这里管免验签, 路由管转发), 单点见 gateway application.yml */
    private final List<String> whitelist;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String internalToken;

    public GatewayAuthFilter(ReactiveStringRedisTemplate redisTemplate,
                             @Value("${jwt.secret}") String jwtSecret,
                             @Value("${my-youtube.internal-token:}") String internalToken,
                             @Value("${my-youtube.auth.whitelist}") List<String> whitelist) {
        this.redisTemplate = redisTemplate;
        this.internalToken = internalToken;
        this.whitelist = whitelist;
        JwtUtil.init(jwtSecret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = normalizePath(exchange.getRequest().getPath().value());
        if (whitelist.stream().anyMatch(path::equals)) {
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

        String token = extractToken(exchange);
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

    private String extractToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
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
