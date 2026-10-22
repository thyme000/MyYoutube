package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable.class)
    public org.springframework.http.ResponseEntity<?> handleModelUnavailable(
            cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable error) {
        log.warn("Model unavailable resource={} reason={}", error.resource(), error.code());
        return org.springframework.http.ResponseEntity.status(503)
                .header("Retry-After", Integer.toString(error.retryAfterSeconds()))
                .body(java.util.Map.of("detail", java.util.Map.of("resource",
                        error.resource() == null ? "UNKNOWN" : error.resource(), "code", error.code())));
    }

    @ExceptionHandler(cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure.class)
    public org.springframework.http.ResponseEntity<?> handleModelFailure(
            cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure error) {
        log.warn("Model execution failed resource={} reason={}",error.resource(),error.getCause()==null ? error.getMessage() : error.getCause().getClass().getSimpleName());
        return org.springframework.http.ResponseEntity.status(502).body(java.util.Map.of("detail",
                java.util.Map.of("resource",error.resource(),"code","MODEL_EXECUTION_FAILED")));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> handleBizException(BizException error, WebRequest request) {
        log.warn("Business rejection code={}", error.getErrorCode().getCode());
        return respond(error.getErrorCode(), error.getMessage(), request);
    }

    @ExceptionHandler(DependencyException.class)
    public ResponseEntity<Object> handleDependencyException(DependencyException error, WebRequest request) {
        log.error("Dependency failure code={}", error.getErrorCode().getCode(), error);
        return respond(error.getErrorCode(), error.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException error, WebRequest request) {
        return respond(ErrorCode.INVALID_ARGUMENT, ErrorCode.INVALID_ARGUMENT.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        ErrorCode code = switch (status.value()) {
            case 400 -> error instanceof MethodArgumentNotValidException || error instanceof HandlerMethodValidationException
                    ? ErrorCode.INVALID_ARGUMENT : ErrorCode.INVALID_REQUEST;
            case 404 -> ErrorCode.ENDPOINT_NOT_FOUND;
            default -> ErrorCode.forHttpStatus(status.value());
        };
        if (code.getHttpStatus() >= 500) log.error("Request processing failed code={}", code.getCode(), error);
        return super.handleExceptionInternal(error, Result.fail(code), headers,
                HttpStatusCode.valueOf(code.getHttpStatus()), request);
    }

    @Override
    protected ResponseEntity<Object> handleAsyncRequestNotUsableException(AsyncRequestNotUsableException error,
                                                                          WebRequest request) {
        log.debug("Client disconnected from asynchronous request");
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception error, WebRequest request) {
        log.error("Unexpected request failure", error);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), request);
    }

    private ResponseEntity<Object> respond(ErrorCode code, String message, WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            HttpServletResponse response = servlet.getResponse();
            if (response != null && response.isCommitted()) return null;
        }
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code, message));
    }
}
