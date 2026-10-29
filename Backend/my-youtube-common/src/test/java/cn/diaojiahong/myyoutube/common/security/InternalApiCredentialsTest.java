package cn.diaojiahong.myyoutube.common.security;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class InternalApiCredentialsTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " \t "})
    void blankConfigurationDisablesSourceValidation(String configuredToken) {
        var credentials = new InternalApiCredentials(configuredToken);

        assertDoesNotThrow(() -> credentials.require(null));
        assertDoesNotThrow(() -> credentials.require(""));
        assertDoesNotThrow(() -> credentials.require("arbitrary-token"));
    }

    @Test
    void configuredTokenAcceptsAnExactMatch() {
        var credentials = new InternalApiCredentials("expected-token");

        assertDoesNotThrow(() -> credentials.require("expected-token"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong", " expected-token", "expected-token "})
    void configuredTokenRejectsMissingOrMismatchedCredentials(String token) {
        var credentials = new InternalApiCredentials("expected-token");

        BizException error = assertThrows(BizException.class, () -> credentials.require(token));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
    }
}
