package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.security.AuthConstants;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InternalTokenFeignConfiguration {
    @Bean
    public RequestInterceptor internalApiToken(@Value("${my-youtube.internal-token:}") String internalToken) {
        return template -> {
            if (!internalToken.isBlank()) {
                template.header(AuthConstants.INTERNAL_TOKEN_HEADER, internalToken);
            }
        };
    }
}
