package cn.diaojiahong.myyoutube.gateway.filter;

import cn.diaojiahong.myyoutube.gateway.auth.AuthContext;
import cn.diaojiahong.myyoutube.gateway.auth.GatewayAuthChain;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.common.chain.ChainStepRegistry;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关统一鉴权的编排器: 只做请求分类与决策落地, 判定逻辑全部在鉴权步骤链上
 * (白名单 → 连接票 → Bearer 验签 → 作废校验, 通用链见 security.chain.ChainStepRegistry, 见决策即短路)。
 * 通过后把 userId 透传给下游服务(X-User-Id 头), 配置了内部密钥时附带 X-Internal-Token;
 * 凭证验完即从 query 剥掉换头转发, 下游链路不出现明文凭证。
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    /** 长连接转发前必须从 query 剥掉的凭证参数(token 兜底防旧客户端把凭证塞 query) */
    private static final List<String> CONNECTION_CREDENTIAL_PARAMS = List.of("token", "ticket");

    /** 长连接(聊天 WS / SSE 门铃)前缀 */
    private static final List<String> CONNECTION_PREFIXES = List.of("/ws/", "/sse/");

    private final ChainStepRegistry chainRegistry;
    private final String internalToken;
    private final ObjectMapper objectMapper;

    public GatewayAuthFilter(ChainStepRegistry chainRegistry,
                             @org.springframework.beans.factory.annotation.Value("${jwt.secret}") String jwtSecret,
                             @org.springframework.beans.factory.annotation.Value("${my-youtube.internal-token:}") String internalToken,
                             ObjectMapper objectMapper) {
        this.chainRegistry = chainRegistry;
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        cn.diaojiahong.myyoutube.security.JwtUtil.init(jwtSecret);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = normalizePath(exchange.getRequest().getPath().value());
        boolean connectionPath = CONNECTION_PREFIXES.stream().anyMatch(path::startsWith);
        AuthContext ctx = AuthContext.initial(exchange, path, connectionPath);

        return chainRegistry.execute(GatewayAuthChain.GROUP, ctx, AuthContext::decided)
                // 决策要等链跑完才成立, 必须 defer: then 的参数会立即求值, 直接调用会在组装期就把 401 写进响应
                .then(Mono.defer(() -> applyDecision(exchange, chain, ctx)));
    }

    private Mono<Void> applyDecision(ServerWebExchange exchange, GatewayFilterChain chain, AuthContext ctx) {
        return switch (ctx.getDecision()) {
            case SKIP -> chain.filter(exchange);
            case AUTHENTICATED -> {
                ServerHttpRequest request = buildDownstreamRequest(
                        exchange,
                        String.valueOf(ctx.getPrincipalUserId()),
                        ctx.isConnectionPath());
                yield chain.filter(exchange.mutate().request(request).build());
            }
            // REJECT 与"链走完仍无凭证"统一 401, 不区分原因避免给探测者提供信息
            default -> unauthorized(exchange);
        };
    }

    private ServerHttpRequest buildDownstreamRequest(
            ServerWebExchange exchange,
            String userId,
            boolean connectionPath) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                .header(AuthConstants.USER_ID_HEADER, userId);
        if (!internalToken.isBlank()) {
            // 下游 UserIdHeaderFilter 据此拒绝绕过网关直连时伪造的 X-User-Id
            builder.header(AuthConstants.INTERNAL_TOKEN_HEADER, internalToken);
        }
        if (connectionPath) {
            builder.uri(stripConnectionParams(exchange.getRequest().getURI()));
        }
        return builder.build();
    }

    private static java.net.URI stripConnectionParams(java.net.URI uri) {
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
        response.setStatusCode(HttpStatus.valueOf(ErrorCode.UNAUTHORIZED.getHttpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(Result.fail(ErrorCode.UNAUTHORIZED)))
                .flatMap(body -> {
                    DataBuffer buffer = response.bufferFactory().wrap(body);
                    return response.writeWith(Mono.just(buffer));
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
