package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.admin.controller.AdminContentVectorDeadLetterController;
import cn.diaojiahong.myyoutube.admin.controller.AdminMediaRetryController;
import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.common.feign.FeignExceptionHandler;
import cn.diaojiahong.myyoutube.common.feign.InternalFeignConfiguration;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.handler.UnexpectedExceptionHandler;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionFeignContractTest {
    final long id = 9_007_199_254_740_993L;

    final AtomicReference<Object> reply = new AtomicReference<>();

    final AtomicReference<JsonNode> input = new AtomicReference<>();

    final AtomicReference<String> path = new AtomicReference<>();

    final AtomicReference<String> internalToken = new AtomicReference<>();

    final AtomicInteger calls = new AtomicInteger();
    ObjectMapper mapper;
    HttpServer server;

    @BeforeEach
    void start() throws Exception {
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        mapper = builder.build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().toString());
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            input.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = mapper.writeValueAsBytes(reply.get());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        });
        server.start();
        UserContext.setUser("1");
    }

    @AfterEach
    void stop() {
        UserContext.removeUser();
        server.stop(0);
    }

    ApplicationContextRunner runner() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withPropertyValues("my-youtube.transcode-service.url=" + url, "my-youtube.search-service.url=" + url,
                        "my-youtube.internal-token=decision-test-token")
                .withUserConfiguration(Clients.class);
    }

    MockMvc mvc(MediaRetryPort retries, ContentVectorDeadLetterPort deadLetters) {
        return MockMvcBuilders.standaloneSetup(new AdminMediaRetryController(retries, AuthorizationFixture.authorization()),
                        new AdminContentVectorDeadLetterController(deadLetters, AuthorizationFixture.authorization()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(
                        new GlobalExceptionHandler(),
                        new FeignExceptionHandler(),
                        new UnexpectedExceptionHandler())
                .build();
    }

    @Test
    void admissionKeepsAcceptedAndBusyDecisionsAcrossTheHttpBoundary() {
        var batch = new MediaRetryPort.Batch(
                id,
                MediaRetryBatchStatus.RUNNING,
                1,
                0,
                1,
                0,
                0,
                0,
                1,
                1,
                null,
                null,
                null);
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(
                    context.getBean(MediaRetryPort.class),
                    context.getBean(ContentVectorDeadLetterPort.class));
            for (boolean accepted : new boolean[]{true, false}) {
                String code = accepted ? "RETRY_BATCH_CREATED" : "RETRY_BATCH_RUNNING";
                reply.set(new MediaRetryPort.Admission(accepted, code, "补跑决策", batch));
                mvc.perform(post("/admin/media-retry-batches").contentType("application/json")
                                .content("{\"requestId\":\"contract-" + accepted + "\",\"taskIds\":[\"" + id + "\"]}"))
                        .andExpect(status().is(accepted ? 202 : 409))
                        .andExpect(jsonPath("$.code").value(accepted ? 200 : 40901))
                        .andExpect(jsonPath("$.data.accepted").value(accepted))
                        .andExpect(jsonPath("$.data.code").value(code))
                        .andExpect(jsonPath("$.data.batch.batchId").value(Long.toString(id)))
                        .andExpect(jsonPath("$.data.data").doesNotExist());
                assertEquals("/internal/media-retries/batches?actorId=1", path.get());
                assertEquals("contract-" + accepted, input.get().path("requestId").asText());
                assertEquals(id, input.get().path("taskIds").get(0).asLong());
            }
            assertEquals(2, calls.get());
        });
    }

    @ParameterizedTest
    @CsvSource({"ACCEPTED,202,200", "ALREADY_COMPLETED,200,200", "INELIGIBLE,409,40901", "DISABLED,409,40901",
            "NOT_FOUND,404,40401", "INVALID_MESSAGE,400,40001", "STATE_UNAVAILABLE,503,50301", "DELIVERY_UNCONFIRMED,503,50301"})
    void replayKeepsDecisionsAndOriginalMessageIdentityAcrossHttp(ContentVectorDeadLetterPort.ReplayStatus decision,
                                                                 int status, int code) {
        reply.set(new ContentVectorDeadLetterPort.ReplayResult(decision, id, "重放决策"));
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            mvc(context.getBean(MediaRetryPort.class), context.getBean(ContentVectorDeadLetterPort.class))
                    .perform(post("/admin/dead-letters/content-vectors/messages/replay").contentType("application/json")
                            .content("{\"brokerName\":\"fixture\",\"queueId\":0,\"offset\":\"" + id + "\"}"))
                    .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.data.status").value(decision.name()))
                    .andExpect(jsonPath("$.data.contentId").value(Long.toString(id)))
                    .andExpect(jsonPath("$.data.message").value("重放决策"));
            assertEquals("/internal/dead-letters/content-vectors/messages/replay?actorId=1", path.get());
            assertEquals("decision-test-token", internalToken.get());
            assertEquals("fixture", input.get().path("brokerName").asText());
            assertEquals(0, input.get().path("queueId").asInt());
            assertEquals(id, input.get().path("offset").asLong());
            assertEquals(1, calls.get());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {MediaRetryPort.class, ContentVectorDeadLetterPort.class}, defaultConfiguration = InternalFeignConfiguration.class)
    static class Clients {
    }
}
