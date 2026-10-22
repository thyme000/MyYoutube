package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeignExceptionHandlerTest {
    @Test void upstreamAuthAndInvalidSuccessDoNotBecomeUserAuthErrors() throws Exception {
        var request = Request.create(Request.HttpMethod.GET, "http://private-service", Map.of(), null, StandardCharsets.UTF_8, null);
        for (var error : new FeignException[]{
                FeignException.errorStatus("private", Response.builder().status(401).reason("private").request(request).headers(Map.of()).build()),
                new DecodeException(200, "private response", request)}) {
            MockMvcBuilders.standaloneSetup(new Endpoint(error))
                    .setControllerAdvice(new GlobalExceptionHandler(), new FeignExceptionHandler()).build()
                    .perform(get("/remote"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50201))
                    .andExpect(jsonPath("$.message").value("依赖服务调用失败，请稍后再试"));
        }
    }

    @Test void noResponseTimeoutIs504WithoutLosingTheGlobalErrorEnvelope() throws Exception {
        var request = Request.create(Request.HttpMethod.GET, "http://private-service", Map.of(), null, StandardCharsets.UTF_8, null);
        var timeout = new RetryableException(-1, "private timeout", Request.HttpMethod.GET,
                new SocketTimeoutException(), (Long) null, request);
        MockMvcBuilders.standaloneSetup(new Endpoint(timeout))
                .setControllerAdvice(new GlobalExceptionHandler(), new FeignExceptionHandler()).build()
                .perform(get("/remote"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value(50401));
    }

    @RestController
    static class Endpoint {
        final FeignException error;
        Endpoint(FeignException error) { this.error = error; }
        @GetMapping("/remote") public Object call() { throw error; }
    }
}
