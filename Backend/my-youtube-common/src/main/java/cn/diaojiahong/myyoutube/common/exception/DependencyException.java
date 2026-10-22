package cn.diaojiahong.myyoutube.common.exception;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import lombok.Getter;

@Getter
public class DependencyException extends RuntimeException {
    private final ErrorCode errorCode;

    public DependencyException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode != ErrorCode.DEPENDENCY_FAILURE && errorCode != ErrorCode.DEPENDENCY_UNAVAILABLE
                && errorCode != ErrorCode.DEPENDENCY_TIMEOUT) {
            throw new IllegalArgumentException("Dependency failure requires a dependency error code");
        }
        this.errorCode = errorCode;
    }
}
