package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.Result;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RestControllerAdvice
@ConditionalOnClass(name = "feign.FeignException")
public class FeignExceptionHandler {
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Result<?>> failure(FeignException error, WebRequest request) {
        DependencyException failure = FeignFailures.classify(error);
        log.error("Remote call failed code={}", failure.getErrorCode().getCode(), failure);
        if (request instanceof ServletWebRequest servlet && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()) {
            return null;
        }
        return ResponseEntity.status(failure.getErrorCode().getHttpStatus())
                .body(Result.fail(failure.getErrorCode(), failure.getMessage()));
    }
}
