package cn.diaojiahong.myyoutube.gateway.filter;

import cn.diaojiahong.myyoutube.common.chain.ChainStepRegistry;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayAuthFilterTest {
    @Test void unauthenticatedRequestUsesTheSharedErrorContract() throws Exception {
        var mapper = new ObjectMapper();
        var filter = new GatewayAuthFilter(new ChainStepRegistry(List.of()),
                "test-secret-at-least-thirty-two-characters", "", mapper);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/private"));
        filter.filter(exchange, ignored -> { throw new AssertionError("Rejected request reached downstream"); }).block();
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.get("code").asInt());
        assertEquals(ErrorCode.UNAUTHORIZED.getMessage(), body.get("message").asText());
        assertTrue(body.get("data").isNull());
    }
}
