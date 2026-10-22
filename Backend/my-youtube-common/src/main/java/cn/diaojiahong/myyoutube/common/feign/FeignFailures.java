package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import feign.FeignException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

public final class FeignFailures {
    private FeignFailures() {}

    public static DependencyException classify(FeignException error) {
        return fromStatus(error.status(), error);
    }

    static DependencyException fromStatus(int status, Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException || cause instanceof TimeoutException)
                return failure(ErrorCode.DEPENDENCY_TIMEOUT, error);
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException)
                return failure(ErrorCode.DEPENDENCY_UNAVAILABLE, error);
            if (cause == cause.getCause()) break;
        }
        return failure(switch (status) {
            case 503 -> ErrorCode.DEPENDENCY_UNAVAILABLE;
            case 504 -> ErrorCode.DEPENDENCY_TIMEOUT;
            default -> ErrorCode.DEPENDENCY_FAILURE;
        }, error);
    }

    private static DependencyException failure(ErrorCode code, Throwable cause) {
        return new DependencyException(code, code.getMessage(), cause);
    }
}
