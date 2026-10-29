package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.security.AuthConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InternalTokenFeignConfigurationTest {
    private final InternalTokenFeignConfiguration configuration = new InternalTokenFeignConfiguration();

    @ParameterizedTest
    @ValueSource(strings = {"", " \t "})
    void blankConfigurationSendsNoCredentialsAndDoesNotFail(String configuredToken) {
        RequestInterceptor interceptor = configuration.internalApiToken(configuredToken);
        var template = new RequestTemplate();

        assertDoesNotThrow(() -> interceptor.apply(template));
        assertFalse(template.headers().containsKey(AuthConstants.INTERNAL_TOKEN_HEADER));
    }

    @Test
    void configuredTokenIsForwardedUnchanged() {
        RequestInterceptor interceptor = configuration.internalApiToken("expected-token");
        var template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(List.of("expected-token"), template.headers().get(AuthConstants.INTERNAL_TOKEN_HEADER));
    }
}
