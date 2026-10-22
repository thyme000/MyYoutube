package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.feign.InternalApiErrorDecoder;
import cn.diaojiahong.myyoutube.common.feign.InternalFeignConfiguration;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import com.sun.net.httpserver.HttpServer;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.context.annotation.Configuration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class FeignConfigurationTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                FeignAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withPropertyValues("my-youtube.video-service.url=http://127.0.0.1:1", "my-youtube.llm-service.url=http://127.0.0.1:1");
    }

    @Test void springClientsUseTheSharedDecoderAndDoNotRetryBusinessRejections() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/admin/content/media", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"code\":40401,\"message\":\"媒体不存在\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            runner().withPropertyValues("my-youtube.video-service.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .withUserConfiguration(Clients.class).run(context -> {
                        assertThat(context).hasNotFailed();
                        var factory = context.getBean(FeignClientFactory.class);
                        assertInstanceOf(InternalApiErrorDecoder.class, factory.getInstance("contentAdminPort", ErrorDecoder.class));
                        assertSame(Retryer.NEVER_RETRY, factory.getInstance("contentAdminPort", Retryer.class));
                        var error = assertThrows(BizException.class, () -> context.getBean(ContentAdminPort.class)
                                .media(new ContentAdminPort.ContentId(7L)));
                        assertEquals("媒体不存在", error.getMessage());
                        assertEquals(1, calls.get());
                    });
        } finally { server.stop(0); }
    }

    @Test void anExistingSpecializedDecoderIsNotReplaced() {
        ErrorDecoder specialized = (method, response) -> new IllegalStateException("specialized");
        runner().withBean(ErrorDecoder.class, () -> specialized).withUserConfiguration(Clients.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertSame(specialized, context.getBean(FeignClientFactory.class).getInstance("contentAdminPort", ErrorDecoder.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = ContentAdminPort.class, defaultConfiguration = InternalFeignConfiguration.class)
    static class Clients {}
}
