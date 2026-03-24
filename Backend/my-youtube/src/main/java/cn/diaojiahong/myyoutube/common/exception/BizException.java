package cn.diaojiahong.myyoutube.common.exception;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BizException extends RuntimeException {

    public static final int OSS_ERROR = 10001;

    private final int code;
    private final String message;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }
}
