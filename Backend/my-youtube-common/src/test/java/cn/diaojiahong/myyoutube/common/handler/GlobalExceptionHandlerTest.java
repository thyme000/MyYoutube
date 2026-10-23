package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"INVALID_ARGUMENT", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "STATE_CONFLICT", "RATE_LIMITED"})
    void businessRejectionsKeepTheirHttpAndBusinessCodes(ErrorCode code) throws Exception {
        mvc.perform(get("/contract/rejection/{code}", code.name()))
                .andExpect(status().is(code.getHttpStatus()))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test void bindingAndBodyValidationAreClientErrors() throws Exception {
        mvc.perform(get("/contract/parameter")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
        mvc.perform(get("/contract/parameter").param("count", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40002));
        mvc.perform(post("/contract/input").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40002));
        mvc.perform(post("/contract/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
    }

    @Test void methodInputValidationIs400ButReturnContractViolationIs500() throws Exception {
        mvc.perform(get("/contract/validated").param("count", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        mvc.perform(get("/contract/invalid-return"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(50001));
    }

    @Test void protocolErrorsKeepAllowAndUseTheErrorEnvelope() throws Exception {
        mvc.perform(post("/contract/parameter")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.code").value(40501));
        mvc.perform(post("/contract/input").contentType(MediaType.TEXT_PLAIN).content("input"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(41501));
        mvc.perform(get("/contract/upload-error"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(41301));
    }

    @Test void internalArgumentFailureDoesNotExposeItsMessageOrBecome400() throws Exception {
        mvc.perform(get("/contract/internal-error"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    @Test void dependencyFailuresKeepTheirCauseAndSafePublicMessage() throws Exception {
        var cause = new IllegalStateException("private database endpoint");
        var error = new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "资料暂时不可用", cause);
        assertSame(cause, error.getCause());
        mvc.perform(get("/contract/dependency"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301))
                .andExpect(jsonPath("$.message").value("资料暂时不可用"));
        assertThrows(IllegalArgumentException.class, () -> new BizException(ErrorCode.INTERNAL_ERROR));
    }

    @Test void committedResponseIsNotOverwritten() throws Exception {
        var response = new MockHttpServletResponse();
        response.getWriter().write("stream data");
        response.flushBuffer();
        var result = new GlobalExceptionHandler().handleBizException(new BizException(ErrorCode.FORBIDDEN),
                new ServletWebRequest(new MockHttpServletRequest(), response));
        assertNull(result);
        assertEquals("stream data", response.getContentAsString());
    }

    @Test void modelAdmissionUsesTheCommonEnvelopeAndKeepsRetryInformation() throws Exception {
        mvc.perform(get("/contract/model"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value(50302))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.data.resource").value("OCR"))
                .andExpect(jsonPath("$.data.reason").value("MODEL_RECOVERING"));
    }

    @Test void modelExecutionFailureUsesSafeMessageAndStructuredReason() throws Exception {
        mvc.perform(get("/contract/model-failure"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(50202))
                .andExpect(jsonPath("$.message").value(ErrorCode.MODEL_EXECUTION_FAILED.getMessage()))
                .andExpect(jsonPath("$.data.resource").value("LLM"))
                .andExpect(jsonPath("$.data.reason").value("MODEL_EXECUTION_FAILED"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private upstream"))));
    }

    @Test void modelErrorsDoNotOverwriteCommittedResponsesOrRequireAResource() throws Exception {
        var response = new MockHttpServletResponse();
        response.getWriter().write("stream data");
        response.flushBuffer();
        var request = new ServletWebRequest(new MockHttpServletRequest(), response);
        var handler = new GlobalExceptionHandler();
        assertNull(handler.handleModelUnavailable(new ModelUnavailable("MODEL_RECOVERING", 30, null), request));
        assertNull(handler.handleModelFailure(new ModelCallFailure(null, null), request));
        assertEquals("stream data", response.getContentAsString());
        var uncommitted = new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
        assertEquals(502, handler.handleModelFailure(new ModelCallFailure(null, null), uncommitted).getStatusCode().value());
    }

    @Test void businessRejectionLogsDoNotIncludeMessagesOrStackTraces() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(get("/contract/rejection/INVALID_ARGUMENT")).andExpect(status().isBadRequest());
            assertFalse(events.list.isEmpty());
            assertTrue(events.list.stream().allMatch(event -> event.getThrowableProxy() == null));
            assertTrue(events.list.stream().noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR));
            assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains(ErrorCode.INVALID_ARGUMENT.getMessage())));
        } finally { logger.detachAppender(events); events.stop(); }
    }

    @Test void unmappedRouteKeeps404() throws Exception {
        mvc.perform(get("/no-such-route"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40402));
    }

    @RestController
    static class Endpoints {
        @GetMapping("/contract/rejection/{code}")
        public Result<?> rejection(@PathVariable ErrorCode code) { throw new BizException(code); }
        @GetMapping("/contract/parameter")
        public Result<?> parameter(@RequestParam int count) { return Result.success(count); }
        @PostMapping(value = "/contract/input", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Result<?> input(@Valid @RequestBody Input input) { return Result.success(input); }
        @GetMapping("/contract/validated")
        public Result<?> validated(@RequestParam @Min(1) int count) { return Result.success(count); }
        @GetMapping("/contract/invalid-return") @NotNull
        public String invalidReturn() { return null; }
        @GetMapping("/contract/internal-error")
        public Result<?> internal() { throw new IllegalArgumentException("private invariant details"); }
        @GetMapping("/contract/dependency")
        public Result<?> dependency() {
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "资料暂时不可用",
                    new IllegalStateException("private database endpoint"));
        }
        @GetMapping("/contract/upload-error")
        public Result<?> upload() { throw new MaxUploadSizeExceededException(10); }
        @GetMapping("/contract/model")
        public Result<?> model() { throw new ModelUnavailable("OCR", "MODEL_RECOVERING", 30, null); }
        @GetMapping("/contract/model-failure")
        public Result<?> modelFailure() { throw new ModelCallFailure("LLM", new IllegalStateException("private upstream")); }
    }
    record Input(@NotBlank String name) {}
}
