package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class UnexpectedExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> failure(Exception error, WebRequest request) {
        log.error("Unexpected request failure", error);
        if (request instanceof ServletWebRequest servlet && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()) return null;
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus()).body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }
}
