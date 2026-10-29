package cn.diaojiahong.myyoutube.common.security;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalApiCredentials {
    private final String internalToken;

    public InternalApiCredentials(@Value("${my-youtube.internal-token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    public void require(String token) {
        if (!internalToken.isBlank() && !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "非法请求来源");
        }
    }
}
