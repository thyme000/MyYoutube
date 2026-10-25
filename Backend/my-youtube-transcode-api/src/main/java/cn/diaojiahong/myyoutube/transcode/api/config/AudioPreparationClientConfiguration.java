package cn.diaojiahong.myyoutube.transcode.api.config;

import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

public class AudioPreparationClientConfiguration {
    @Bean
    public FeignBuilderCustomizer audioPreparationTimeouts(
            @Value("${my-youtube.audio-processing.connect-timeout-ms:500}") int connectMs,
            @Value("${my-youtube.audio-processing.read-timeout-ms:1500}") int readMs) {
        return builder -> builder.options(new Request.Options(connectMs, TimeUnit.MILLISECONDS, readMs, TimeUnit.MILLISECONDS, false))
                .retryer(Retryer.NEVER_RETRY);
    }
}
