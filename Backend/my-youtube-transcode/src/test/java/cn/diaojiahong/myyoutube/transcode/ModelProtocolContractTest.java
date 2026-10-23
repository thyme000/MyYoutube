package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.feign.InternalFeignConfiguration;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.transcode.api.*;
import cn.diaojiahong.myyoutube.transcode.config.FeignRetryConfiguration;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.service.*;
import cn.diaojiahong.myyoutube.transcode.task.MediaTaskExecutor;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.*;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ModelProtocolContractTest {
    final AtomicReference<RuntimeException> javaFailure = new AtomicReference<>();
    final AtomicReference<String> runtimeBody = new AtomicReference<>("{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}");
    final AtomicReference<String> javaBodyOverride = new AtomicReference<>();
    final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final TagRequest request = new TagRequest("title", "description", "summary");
    HttpServer server;
    String url;

    @BeforeEach void start() throws Exception {
        javaFailure.set(new ModelUnavailable("LLM", "MODEL_QUEUE_FULL", 50, null));
        var mvc = MockMvcBuilders.standaloneSetup(new TagEndpoint(javaFailure))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            byte[] input = exchange.getRequestBody().readAllBytes();
            int status;
            byte[] body;
            if (path.equals("/internal/content-pipeline/tags") && javaBodyOverride.get() == null) {
                try {
                    var response = mvc.perform(post(path).contentType("application/json").content(input)).andReturn().getResponse();
                    status = response.getStatus(); body = response.getContentAsByteArray();
                    for (String name : response.getHeaderNames()) exchange.getResponseHeaders().put(name, List.copyOf(response.getHeaders(name)));
                } catch (Exception error) { throw new java.io.IOException(error); }
            } else {
                status = path.equals("/internal/media-retries/query") ? 400 : 503;
                String text = status == 400 ? "{\"code\":40001,\"message\":\"非法筛选\",\"data\":null}"
                        : path.equals("/internal/content-pipeline/tags") ? javaBodyOverride.get() : runtimeBody.get();
                body = text.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Retry-After", "30");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withPropertyValues("my-youtube.asr.url=" + url, "my-youtube.ocr.url=" + url,
                        "my-youtube.video-service.url=" + url, "my-youtube.transcode-service.url=" + url)
                .withUserConfiguration(Clients.class);
    }

    @Test void clientScopesRoundTripBothProtocolsAndKeepTheOrdinaryBusinessDecoder() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean(FeignClientFactory.class);
            var internal = context.getBean(ErrorDecoder.class);
            assertSame(internal, factory.getInstance("contentPipelinePort", ErrorDecoder.class));
            assertSame(internal, factory.getInstance("mediaRetryPort", ErrorDecoder.class));
            assertNotSame(internal, factory.getInstance("asrPort", ErrorDecoder.class));
            assertNotSame(internal, factory.getInstance("ocrPort", ErrorDecoder.class));
            for (String client : List.of("contentPipelinePort", "asrPort", "ocrPort", "mediaRetryPort"))
                assertSame(Retryer.NEVER_RETRY, factory.getInstance(client, Retryer.class));
            var tag = assertThrows(ModelUnavailable.class, () -> context.getBean(ContentPipelinePort.class).generateTags(request));
            assertEquals("LLM", tag.resource()); assertEquals("MODEL_QUEUE_FULL", tag.code());
            assertEquals(50, tag.retryAfterSeconds()); assertInstanceOf(FeignException.class, tag.getCause());
            var asr = assertThrows(ModelUnavailable.class, () -> context.getBean(AsrPort.class)
                    .transcribe(new AsrPort.Request("source", "vtt", "segments", "title", "tags", "description", "zh")));
            assertEquals("ASR", asr.resource());
            var ocr = assertThrows(ModelUnavailable.class, () -> context.getBean(OcrPort.class).recognize(new OcrPort.Request(List.of("image"))));
            assertEquals("OCR", ocr.resource());
            assertThrows(BizException.class, () -> context.getBean(MediaRetryPort.class)
                    .query(new MediaRetryPort.Query(null, null, null, null, null, null, null, 0, null)));
            assertEquals(4, calls.size());
            assertTrue(calls.values().stream().allMatch(count -> count.get() == 1));
        });
    }

    @Test void neitherClientAcceptsTheOtherSourceProtocol() {
        javaBodyOverride.set("{\"detail\":{\"resource\":\"LLM\",\"code\":\"MODEL_RECOVERING\"}}");
        runtimeBody.set("{\"code\":50302,\"message\":\"不可用\",\"data\":{\"resource\":\"OCR\",\"reason\":\"MODEL_RECOVERING\"}}");
        runner().run(context -> {
            assertThrows(DependencyException.class, () -> context.getBean(ContentPipelinePort.class).generateTags(request));
            assertThrows(DependencyException.class, () -> context.getBean(OcrPort.class).recognize(new OcrPort.Request(List.of("image"))));
            assertTrue(calls.values().stream().allMatch(count -> count.get() == 1));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_RECOVERING", "MODEL_CIRCUIT_OPEN", "MODEL_EXECUTION_FAILED"})
    void aDecodedHttpFailureEntersTheExistingWorkerBranch(String reason) {
        javaFailure.set(reason.equals("MODEL_EXECUTION_FAILED") ? new ModelCallFailure("LLM", new IllegalStateException("fixture"))
                : new ModelUnavailable("LLM", reason, 30, null));
        runner().run(context -> {
            var tasks = mock(MediaTaskService.class);
            var message = new WorkerCorrelationTest().message();
            message.setTaskType("TAG");
            when(tasks.claim(message)).thenReturn(message);
            var executor = mock(MediaTaskExecutor.class);
            when(executor.type()).thenReturn(MediaTaskType.TAG);
            when(executor.execute(message)).thenAnswer(call -> context.getBean(ContentPipelinePort.class).generateTags(request));
            var worker = new MediaTaskWorker(tasks, List.of(executor));
            try {
                worker.execute(message);
                if (reason.equals("MODEL_EXECUTION_FAILED")) {
                    verify(tasks).completeModelFailure(eq(message), eq("LLM"), contains("ModelCallFailure"));
                    verify(tasks, never()).deferAndAdvance(any(), any());
                } else {
                    verify(tasks).deferAndAdvance(eq(message), argThat(error -> error.code().equals(reason)
                            && error.terminal() == reason.equals("MODEL_CIRCUIT_OPEN") && error.getCause() instanceof FeignException));
                    verify(tasks, never()).completeModelFailure(any(), any(), any());
                }
                verify(tasks, never()).completeAndAdvance(any(), any(), any());
                assertEquals(1, calls.get("/internal/content-pipeline/tags").get());
            } finally { worker.close(); }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(FeignRetryConfiguration.class)
    @EnableFeignClients(clients = {ContentPipelinePort.class, AsrPort.class, OcrPort.class, MediaRetryPort.class},
            defaultConfiguration = InternalFeignConfiguration.class)
    static class Clients {}

    @RestController
    @RequestMapping("/internal/content-pipeline")
    static class TagEndpoint implements ContentPipelinePort {
        final AtomicReference<RuntimeException> failure;
        TagEndpoint(AtomicReference<RuntimeException> failure) { this.failure = failure; }
        @Override public String generateTags(TagRequest request) { throw failure.get(); }
        @Override public ReviewSpec reviewSpec(Long pipelineId, int attempt) { return null; }
    }
}
