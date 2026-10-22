package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import feign.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InternalApiErrorDecoderTest {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, "{}", 0, 0));
    final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    final HttpServer server;
    final Api api;

    InternalApiErrorDecoderTest() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            var current = reply.get();
            try {
                Thread.sleep(current.headerDelay());
                byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(current.status(), current.status() == 204 ? -1 : body.length);
                Thread.sleep(current.bodyDelay());
                if (current.status() != 204) exchange.getResponseBody().write(body);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        api = builder().target(Api.class, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void close() { server.stop(0); executor.shutdownNow(); }

    Feign.Builder builder() {
        return Feign.builder().retryer(Retryer.NEVER_RETRY).errorDecoder(new InternalApiErrorDecoder())
                .options(new Request.Options(500, 200))
                .decoder((response, type) -> JsonUtil.GSON.fromJson(response.body().asReader(StandardCharsets.UTF_8), type));
    }

    @Test void successReturnsDtoGenericPageAndVoidWithoutAnEnvelope() {
        reply.set(new Reply(200, "{\"id\":\"7\",\"name\":\"item\"}", 0, 0));
        assertEquals(new Item("7", "item"), api.item());
        reply.set(new Reply(200, "{\"total\":\"7\",\"pages\":\"2\",\"list\":[{\"id\":\"7\",\"name\":\"item\"}]}", 0, 0));
        var page = api.page();
        assertEquals(7, page.getTotal()); assertEquals(2, page.getPages());
        assertEquals(new Item("7", "item"), page.getList().get(0));
        reply.set(new Reply(204, "", 0, 0));
        assertDoesNotThrow(api::command);
        assertEquals(3, calls.get());
    }

    @Test void declaredBusinessErrorsPreserveCodeMessageAndCause() {
        reply.set(new Reply(404, "{\"code\":40401,\"message\":\"资源不可访问\",\"data\":null}", 0, 0));
        var error = assertThrows(BizException.class, api::item);
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        assertEquals("资源不可访问", error.getMessage());
        assertInstanceOf(FeignException.class, error.getCause());
        assertEquals(1, calls.get());
    }

    @Test void methodPolicyCanRestrictTheInterfacePolicy() {
        reply.set(new Reply(400, "{\"code\":40001,\"message\":\"invalid\"}", 0, 0));
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BizException.class, api::item).getErrorCode());
        assertEquals(ErrorCode.DEPENDENCY_FAILURE, assertThrows(DependencyException.class, api::restricted).getErrorCode());
    }

    @Test void authRoutingBindingAndUnrecognizedErrorsStayDependencyFailures() {
        for (String body : new String[]{"not json", "null", "[]", "{}", "{\"code\":40001}", "{\"code\":\"40001\"}",
                "{\"code\":40001.5}", "{\"code\":40002}", "{\"code\":99999}", "{\"code\":40401}"}) {
            reply.set(new Reply(400, body, 0, 0));
            assertEquals(ErrorCode.DEPENDENCY_FAILURE, assertThrows(DependencyException.class, api::item).getErrorCode(), body);
        }
        for (int status : new int[]{401, 403, 404}) {
            reply.set(new Reply(status, "{\"code\":" + (status == 404 ? 40402 : status * 100 + 1) + ",\"message\":\"private\"}", 0, 0));
            var error = assertThrows(DependencyException.class, api::item);
            assertEquals(ErrorCode.DEPENDENCY_FAILURE, error.getErrorCode());
            assertFalse(error.getMessage().contains("private"));
        }
    }

    @Test void errorBodyReadingHasAnExactByteLimit() {
        String prefix = "{\"code\":40001,\"message\":\"invalid\",\"padding\":\"";
        for (int size : new int[]{8192, 8193}) {
            String body = prefix + "x".repeat(size - prefix.length() - 2) + "\"}";
            reply.set(new Reply(400, body, 0, 0));
            if (size == 8192) assertThrows(BizException.class, api::item);
            else assertThrows(DependencyException.class, api::item);
        }
    }

    @Test void dependencyStatusAndRetryAfterNeverCauseAnAutomaticRetry() {
        for (int status : new int[]{500, 502, 503, 504}) {
            reply.set(new Reply(status, "upstream details", 0, 0));
            int before = calls.get();
            var error = assertThrows(DependencyException.class, api::item);
            assertEquals(status == 503 ? ErrorCode.DEPENDENCY_UNAVAILABLE
                    : status == 504 ? ErrorCode.DEPENDENCY_TIMEOUT : ErrorCode.DEPENDENCY_FAILURE, error.getErrorCode());
            assertEquals(before + 1, calls.get());
        }
    }

    @Test void responseAndBodyTimeoutsAreClassifiedOutsideTheErrorDecoder() {
        for (Reply current : new Reply[]{new Reply(200, "{}", 2000, 0), new Reply(200, "{}", 0, 2000)}) {
            reply.set(current);
            var error = assertThrows(FeignException.class, api::item);
            assertEquals(ErrorCode.DEPENDENCY_TIMEOUT, FeignFailures.classify(error).getErrorCode());
        }
        assertEquals(2, calls.get());
    }

    @Test void readingAnErrorBodyCanAlsoTimeOut() {
        reply.set(new Reply(400, "{}", 0, 2000));
        assertEquals(ErrorCode.DEPENDENCY_TIMEOUT, assertThrows(DependencyException.class, api::item).getErrorCode());
        assertEquals(1, calls.get());
    }

    @Test void connectionRefusalIsUnavailableAndMalformedSuccessIs502() throws Exception {
        int port;
        try (var reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
        var disconnected = builder().target(Api.class, "http://127.0.0.1:" + port);
        var error = assertThrows(FeignException.class, disconnected::item);
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, FeignFailures.classify(error).getErrorCode());
        reply.set(new Reply(200, "not json", 0, 0));
        var malformed = assertThrows(FeignException.class, api::item);
        assertEquals(ErrorCode.DEPENDENCY_FAILURE, FeignFailures.classify(malformed).getErrorCode());
    }

    @Test void cancellationKeepsTheInterruptFlagAndDoesNotRetry() {
        var attempts = new AtomicInteger();
        var cancelled = builder().client((request, options) -> {
            attempts.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("cancelled");
        }).target(Api.class, "http://127.0.0.1:1");
        try {
            assertThrows(FeignException.class, cancelled::item);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, attempts.get());
        } finally { Thread.interrupted(); }
    }

    @PropagateBusinessErrors({ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND, ErrorCode.STATE_CONFLICT})
    interface Api {
        @RequestLine("GET /item") Item item();
        @RequestLine("GET /page") PageResult<Item> page();
        @RequestLine("POST /command") void command();
        @PropagateBusinessErrors(ErrorCode.STATE_CONFLICT)
        @RequestLine("GET /restricted") Item restricted();
    }
    record Item(String id, String name) {}
    record Reply(int status, String body, int headerDelay, int bodyDelay) {}
}
