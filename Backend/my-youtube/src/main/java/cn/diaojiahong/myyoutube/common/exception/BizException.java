package cn.diaojiahong.myyoutube.common.exception;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BizException extends RuntimeException {

    public static final int OSS_ERROR = 10001;
    public static final int TAG_ERROR = 10002;
    public static final int USER_ERROR = 10003;
    public static final int NOTIFICATION_ERROR = 10004;

    private final int code;
    private final String message;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }
}
