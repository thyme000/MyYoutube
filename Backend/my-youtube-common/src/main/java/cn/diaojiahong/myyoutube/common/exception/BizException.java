package cn.diaojiahong.myyoutube.common.exception;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode.getHttpStatus() < 400 || errorCode.getHttpStatus() >= 500) {
            throw new IllegalArgumentException("Business rejection requires a 4xx error code");
        }
        this.errorCode = errorCode;
    }
}
