package cn.diaojiahong.myyoutube.common.feign;

import feign.codec.ErrorDecoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

public class InternalFeignConfiguration {
    @Bean
    @ConditionalOnMissingBean(ErrorDecoder.class)
    public ErrorDecoder internalApiErrorDecoder() {
        return new InternalApiErrorDecoder();
    }
}
