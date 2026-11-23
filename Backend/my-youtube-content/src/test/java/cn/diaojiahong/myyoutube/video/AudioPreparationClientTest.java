package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.transcode.api.AudioPreparationPort;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AudioPreparationClientTest {
    @FeignClient(name = "ordinary-port", url = "${my-youtube.transcode-service.url}")
    interface OrdinaryPort {
        @GetMapping("/ordinary") String get();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {AudioPreparationPort.class, OrdinaryPort.class})
    static class Clients {
    }

    @Test
    void actualFeignClientUsesShortAudioTimeoutAndDoesNotRetryOrChangeOtherClients() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var audioCalls = new AtomicInteger();
        server.createContext("/internal/media/audios/1", exchange -> {
            audioCalls.incrementAndGet();
            try {
                Thread.sleep(350);
                byte[] body = "{\"audioId\":1,\"status\":\"RUNNING\",\"attempt\":1,\"retryCount\":0}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
            } finally {
                exchange.close();
            }
        });
        server.createContext("/ordinary", exchange -> {
            try {
                Thread.sleep(200);
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    FeignAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class))
                    .withUserConfiguration(Clients.class).withPropertyValues(
                            "my-youtube.transcode-service.url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.cloud.openfeign.client.config.default.connectTimeout=5000",
                            "spring.cloud.openfeign.client.config.default.readTimeout=130000",
                            "my-youtube.audio-processing.connect-timeout-ms=100",
                            "my-youtube.audio-processing.read-timeout-ms=80")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        AudioPreparationPort audio = context.getBean(AudioPreparationPort.class);
                        assertThrows(FeignException.class, () -> audio.preparation(1L));
                        assertEquals("ok", context.getBean(OrdinaryPort.class).get());
                        assertEquals(1, audioCalls.get());
                    });
        } finally {
            server.stop(0);
        }
    }
}
