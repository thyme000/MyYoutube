package cn.diaojiahong.myyoutube.llm.api;

import feign.Client;
import feign.Request;
import feign.Response;
import feign.Retryer;
import java.util.concurrent.CompletableFuture;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@FeignClient(name = "my-youtube-llm-service", contextId = "queryEmbeddingPort",
        path = "/internal/llm", url = "${my-youtube.llm-service.url:}",
        configuration = QueryEmbeddingPort.Configuration.class)
public interface QueryEmbeddingPort {
    @PostMapping("/query-embedding")
    LlmPort.EmbeddingsResponse embed(@RequestBody Query request, Request.Options options);

    @GetMapping("/embedding-info")
    Info info(Request.Options options);

    record Info(String version, int dims) {
    }

    record Query(String text, long deadlineEpochMillis) {
    }

    class Configuration {
        @Bean
        public Client queryEmbeddingClient() {
            return new CancellableClient();
        }

        @Bean
        public Retryer queryEmbeddingRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }

    // Feign 默认传输不能靠线程中断取消 socket 读取，查询端独立使用可取消的 HTTP 调用。
    class CancellableClient implements Client {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                    .timeout(Duration.ofMillis(options.readTimeoutMillis()));
            request.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("Content-Length")) values.forEach(value -> builder.header(name, value));
            });
            builder.method(request.httpMethod().name(), request.body() == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.body()));
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            try {
                HttpResponse<byte[]> response = pending.get(options.readTimeoutMillis(), TimeUnit.MILLISECONDS);
                return Response.builder().request(request).status(response.statusCode()).reason("")
                        .headers(new java.util.HashMap<>(response.headers().map())).body(response.body()).build();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("查询向量 RPC 已取消");
            } catch (TimeoutException error) {
                throw new java.net.SocketTimeoutException("查询向量 RPC 预算耗尽");
            } catch (ExecutionException error) {
                throw new IOException("查询向量 RPC 失败", error.getCause());
            } finally {
                pending.cancel(true);
            }
        }
    }
}
