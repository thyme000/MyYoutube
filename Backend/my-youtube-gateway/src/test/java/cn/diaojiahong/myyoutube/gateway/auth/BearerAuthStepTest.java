package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.security.JwtPayload;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.*;

class BearerAuthStepTest {
    @Test
    void verifiedCredentialDoesNotNeedPlaybackMetadataAndStillAwaitsRevocationCheck() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/protected")
                .header(AuthConstants.AUTH_HEADER, "Bearer invalid"));
        AuthContext context = AuthContext.initial(exchange, "/protected", false);
        JwtPayload verified = new JwtPayload(9, 123456);
        context.jwtPayload(verified);

        new BearerAuthStep().apply(context).block();

        assertNull(context.getPlaybackGrant());
        assertSame(verified, context.getJwtPayload());
        assertEquals(AuthContext.Decision.CONTINUE, context.getDecision());
        assertFalse(context.decided());
    }
}
