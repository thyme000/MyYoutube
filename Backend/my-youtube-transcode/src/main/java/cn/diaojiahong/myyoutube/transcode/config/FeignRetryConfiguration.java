package cn.diaojiahong.myyoutube.transcode.config;
import feign.Retryer;
import org.springframework.context.annotation.*;
@Configuration
public class FeignRetryConfiguration {
    @Bean public Retryer retryer() { return Retryer.NEVER_RETRY; }
    @Bean public feign.codec.ErrorDecoder modelErrorDecoder() { return new ModelErrorDecoder(); }
    @Bean public feign.RequestInterceptor businessCorrelationHeaders() {
        return request -> cn.diaojiahong.myyoutube.common.observability.BusinessContext.HEADERS.forEach((key, header) -> {
            String value = org.slf4j.MDC.get(key);
            if (value != null) request.header(header, value);
        });
    }
}
