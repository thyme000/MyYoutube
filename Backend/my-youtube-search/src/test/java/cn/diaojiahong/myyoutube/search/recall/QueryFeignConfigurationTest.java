package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.feign.InternalApiErrorDecoder;
import cn.diaojiahong.myyoutube.common.feign.InternalFeignConfiguration;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import feign.Client;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class QueryFeignConfigurationTest {
    @Test void sharedErrorsDoNotReplaceTheCancellableTransportOrRetryPolicy() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                FeignAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withPropertyValues("my-youtube.llm-service.url=http://127.0.0.1:1")
                .withUserConfiguration(Clients.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    var factory = context.getBean(FeignClientFactory.class);
                    assertInstanceOf(QueryEmbeddingPort.CancellableClient.class, factory.getInstance("queryEmbeddingPort", Client.class));
                    assertSame(Retryer.NEVER_RETRY, factory.getInstance("queryEmbeddingPort", Retryer.class));
                    assertInstanceOf(InternalApiErrorDecoder.class, factory.getInstance("queryEmbeddingPort", ErrorDecoder.class));
                });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = QueryEmbeddingPort.class, defaultConfiguration = InternalFeignConfiguration.class)
    static class Clients {}
}
