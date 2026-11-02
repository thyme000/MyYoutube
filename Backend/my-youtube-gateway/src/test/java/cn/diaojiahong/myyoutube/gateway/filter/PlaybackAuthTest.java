package cn.diaojiahong.myyoutube.gateway.filter;

import cn.diaojiahong.myyoutube.common.chain.ChainStepRegistry;
import cn.diaojiahong.myyoutube.gateway.auth.BearerAuthStep;
import cn.diaojiahong.myyoutube.gateway.auth.PlaybackAuthStep;
import cn.diaojiahong.myyoutube.gateway.auth.TokenVerAuthStep;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.security.JwtUtil;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackAuthTest {
    private static final String SECRET = "test-secret-at-least-thirty-two-characters";
    private final PlaybackTokens tokens = new PlaybackTokens(SECRET, 21600);
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private GatewayAuthFilter filter;

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.empty());
        filter = new GatewayAuthFilter(new ChainStepRegistry(List.of(new PlaybackAuthStep(tokens),
                new BearerAuthStep(), new TokenVerAuthStep(redis))), SECRET, "internal-secret", new ObjectMapper());
    }

    @Test
    void gatewayImportsTheSharedSignerWithoutScanningServletServices() {
        new ApplicationContextRunner().withUserConfiguration(PlaybackAuthStep.class)
                .withPropertyValues("jwt.secret=" + SECRET)
                .run(context -> {
                    assertNotNull(context.getBean(PlaybackTokens.class));
                    assertNotNull(context.getBean(PlaybackAuthStep.class));
                });
    }

    @Test
    void reviewAndViewTicketsCannotBeUsedAcrossTheirResourcePaths() {
        String review = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.REVIEW).token();
        String view = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW).token();
        reject(request(HttpMethod.GET, "/admin/content/123/streams/master.m3u8", view));
        reject(request(HttpMethod.GET, "/contents/123/streams/master.m3u8", review));
        reject(request(HttpMethod.GET, "/admin/content/456/streams/master.m3u8", review));

        MockServerWebExchange exchange = request(HttpMethod.GET, "/admin/content/123/streams/master.m3u8", review);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, next -> { forwarded.set(next); return Mono.empty(); }).block();
        assertNotNull(forwarded.get());
        assertEquals("9", forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.USER_ID_HEADER));
        assertEquals(review, forwarded.get().getRequest().getHeaders().getFirst(PlaybackTokens.INTERNAL_HEADER));
    }

    @Test
    void playlistGetAndHeadNeedOnlyThePlaybackUrl() {
        PlaybackTokens.Grant grant = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW);
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
            MockServerWebExchange exchange = request(method, "/contents/123/streams/720/index.m3u8", grant.token());
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
            filter.filter(exchange, next -> { forwarded.set(next); return Mono.empty(); }).block();
            assertNotNull(forwarded.get());
            assertNull(forwarded.get().getRequest().getURI().getQuery());
            assertEquals("9", forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.USER_ID_HEADER));
            assertEquals("internal-secret", forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.INTERNAL_TOKEN_HEADER));
            assertEquals(grant.token(), forwarded.get().getRequest().getHeaders().getFirst(PlaybackTokens.INTERNAL_HEADER));
            assertEquals("bytes=800-2799", forwarded.get().getRequest().getHeaders().getFirst("Range"));
            assertNull(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.AUTH_HEADER));
        }
    }

    @Test
    void playlistRequestsReuseTheSameTicketAndIgnoreStaleOrForgedHeaders() {
        String ticket = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW).token();
        for (String path : List.of("/contents/123/streams/master.m3u8", "/contents/123/streams/0/index.m3u8")) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                    .queryParam(PlaybackTokens.QUERY_PARAM, ticket)
                    .header(AuthConstants.AUTH_HEADER, "Bearer stale")
                    .header(PlaybackTokens.INTERNAL_HEADER, "forged"));
            filter.filter(exchange, next -> {
                assertEquals(ticket, next.getRequest().getHeaders().getFirst(PlaybackTokens.INTERNAL_HEADER));
                assertNull(next.getRequest().getHeaders().getFirst(AuthConstants.AUTH_HEADER));
                return Mono.empty();
            }).block();
            assertNull(exchange.getResponse().getStatusCode());
        }
        verify(values, times(2)).get(AuthConstants.TOKEN_VER_KEY_PREFIX + 9);
    }

    @Test
    void ticketCannotAuthorizeAnotherVideoBusinessEndpointOrWriteMethod() {
        String ticket = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW).token();
        for (String path : List.of("/contents/123/streams/720/media.mp4", "/contents/456/streams/720/index.m3u8", "/contents/123", "/contents/123/settings")) {
            reject(request(HttpMethod.GET, path, ticket));
        }
        reject(request(HttpMethod.POST, "/contents/123/streams/720/media.mp4", ticket));
        reject(request(HttpMethod.GET, "/contents/123/streams/720/media.mp4", "invalid"));
    }

    @Test
    void missingOrDuplicatePlaybackCredentialsAreRejectedEvenWithAValidAccessToken() {
        MockServerWebExchange missing = MockServerWebExchange.from(MockServerHttpRequest
                .get("/contents/123/streams/master.m3u8").header(AuthConstants.AUTH_HEADER, "Bearer " + JwtUtil.sign(9)));
        reject(missing);
        String ticket = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW).token();
        reject(MockServerWebExchange.from(MockServerHttpRequest.get("/contents/123/streams/master.m3u8")
                .queryParam(PlaybackTokens.QUERY_PARAM, ticket, ticket)));
    }

    @Test
    void passwordChangeAndCorruptRevocationStateRejectPlayback() {
        PlaybackTokens.Grant grant = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW);
        for (String state : List.of(Long.toString(grant.issuedAtMillis() + 1), "broken")) {
            when(values.get(AuthConstants.TOKEN_VER_KEY_PREFIX + 9)).thenReturn(Mono.just(state));
            reject(request(HttpMethod.GET, "/contents/123/streams/master.m3u8", grant.token()));
        }
    }

    @Test
    void accessAuthenticationStillProtectsDetailsAndRemovesForgedPlaybackHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/contents/123")
                .header(AuthConstants.AUTH_HEADER, "Bearer " + JwtUtil.sign(9))
                .header(PlaybackTokens.INTERNAL_HEADER, "forged"));
        filter.filter(exchange, next -> {
            assertEquals("9", next.getRequest().getHeaders().getFirst(AuthConstants.USER_ID_HEADER));
            assertNull(next.getRequest().getHeaders().getFirst(PlaybackTokens.INTERNAL_HEADER));
            return Mono.empty();
        }).block();
        assertNull(exchange.getResponse().getStatusCode());
    }

    private MockServerWebExchange request(HttpMethod method, String path, String ticket) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method,
                        URI.create(path + "?" + PlaybackTokens.QUERY_PARAM + "=" + ticket))
                .header("Range", "bytes=800-2799").header(AuthConstants.USER_ID_HEADER, "999")
                .header(PlaybackTokens.INTERNAL_HEADER, "forged"));
    }

    private void reject(MockServerWebExchange exchange) {
        filter.filter(exchange, ignored -> { throw new AssertionError("Rejected request reached downstream"); }).block();
        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
    }
}
