package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.handler.UnexpectedExceptionHandler;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeignExceptionHandlerTest {
    @Test
    void translatedErrorsKeepTheirContractsWhenTheirCauseIsAFeignException() throws Exception {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://private-service",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        FeignException cause = FeignException.errorStatus("private", Response.builder().status(400).reason("private")
                .request(request).headers(Map.of()).build());
        RuntimeException[] errors = {
                new BizException(ErrorCode.INVALID_ARGUMENT, "非法查询分页", cause),
                new BizException(ErrorCode.NOT_FOUND, "媒体不存在", cause),
                new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "资料暂时不可用", cause),
                new ModelUnavailable("OCR", "MODEL_RECOVERING", 30, cause),
                new ModelCallFailure("LLM", cause)
        };
        ErrorCode[] codes = {ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND, ErrorCode.DEPENDENCY_UNAVAILABLE,
                ErrorCode.MODEL_UNAVAILABLE, ErrorCode.MODEL_EXECUTION_FAILED};
        for (int index = 0; index < errors.length; index++) {
            ResultActions result = MockMvcBuilders.standaloneSetup(new Endpoint(errors[index]))
                    .setControllerAdvice(
                            new GlobalExceptionHandler(),
                            new FeignExceptionHandler(),
                            new UnexpectedExceptionHandler())
                    .build()
                    .perform(get("/remote"))
                    .andExpect(status().is(codes[index].getHttpStatus()))
                    .andExpect(jsonPath("$.code").value(codes[index].getCode()));
            if (errors[index] instanceof ModelUnavailable) result.andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.data.reason").value("MODEL_RECOVERING"));
            if (errors[index] instanceof ModelCallFailure) result.andExpect(jsonPath("$.data.resource").value("LLM"));
        }
    }

    @Test
    void upstreamAuthAndInvalidSuccessDoNotBecomeUserAuthErrors() throws Exception {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://private-service",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        for (FeignException error : new FeignException[]{
                FeignException.errorStatus("private", Response.builder().status(401).reason("private").request(request).headers(Map.of()).build()),
                new DecodeException(200, "private response", request)}) {
            MockMvcBuilders.standaloneSetup(new Endpoint(error))
                    .setControllerAdvice(
                            new GlobalExceptionHandler(),
                            new FeignExceptionHandler(),
                            new UnexpectedExceptionHandler())
                    .build()
                    .perform(get("/remote"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50201))
                    .andExpect(jsonPath("$.message").value("依赖服务调用失败，请稍后再试"));
        }
    }

    @Test
    void noResponseTimeoutIs504WithoutLosingTheGlobalErrorEnvelope() throws Exception {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://private-service",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        var timeout = new RetryableException(-1, "private timeout", Request.HttpMethod.GET,
                new SocketTimeoutException(), (Long) null, request);
        MockMvcBuilders.standaloneSetup(new Endpoint(timeout))
                .setControllerAdvice(
                        new GlobalExceptionHandler(),
                        new FeignExceptionHandler(),
                        new UnexpectedExceptionHandler())
                .build()
                .perform(get("/remote"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value(50401));
    }

    @Test
    void unknownFailuresStillUseTheSafeFallbackAfterTheSpecificAdvice() throws Exception {
        MockMvcBuilders.standaloneSetup(new Endpoint(new IllegalArgumentException("private invariant")))
                .setControllerAdvice(
                        new GlobalExceptionHandler(),
                        new FeignExceptionHandler(),
                        new UnexpectedExceptionHandler())
                .build()
                .perform(get("/remote"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    @RestController
    static class Endpoint {
        final RuntimeException error;

        Endpoint(RuntimeException error) {
            this.error = error;
        }

        @GetMapping("/remote")
        public Object call() {
            throw error;
        }
    }
}
