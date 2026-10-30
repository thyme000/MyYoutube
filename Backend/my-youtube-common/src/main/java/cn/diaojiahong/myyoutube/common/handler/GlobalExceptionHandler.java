package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure;
import cn.diaojiahong.myyoutube.common.resilience.ModelErrorData;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
// Match translated errors before an advice can match their transport-level causes.
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ModelUnavailable.class)
    public ResponseEntity<Object> handleModelUnavailable(ModelUnavailable error, WebRequest request) {
        log.warn("Model unavailable resource={} reason={}", error.resource(), error.code());
        var headers = new HttpHeaders();
        headers.set("Retry-After", Integer.toString(error.retryAfterSeconds()));
        var data = new ModelErrorData(error.resource(), error.code());
        return respond(ErrorCode.MODEL_UNAVAILABLE, Result.fail(ErrorCode.MODEL_UNAVAILABLE,
                ErrorCode.MODEL_UNAVAILABLE.getMessage(), data), headers, request);
    }

    @ExceptionHandler(ModelCallFailure.class)
    public ResponseEntity<Object> handleModelFailure(ModelCallFailure error, WebRequest request) {
        log.error("Model execution failed resource={}", error.resource(), error);
        var data = new ModelErrorData(error.resource(), "MODEL_EXECUTION_FAILED");
        return respond(ErrorCode.MODEL_EXECUTION_FAILED, Result.fail(ErrorCode.MODEL_EXECUTION_FAILED,
                ErrorCode.MODEL_EXECUTION_FAILED.getMessage(), data), new HttpHeaders(), request);
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
            case 400 -> error instanceof MethodArgumentNotValidException
                    || error instanceof HandlerMethodValidationException
                    ? ErrorCode.INVALID_ARGUMENT : ErrorCode.INVALID_REQUEST;
            case 404 -> ErrorCode.ENDPOINT_NOT_FOUND;
            default -> ErrorCode.forHttpStatus(status.value());
        };
        if (code.getHttpStatus() >= 500) {
            log.error("Request processing failed code={}", code.getCode(), error);
        }
        return super.handleExceptionInternal(error, Result.fail(code), headers,
                HttpStatusCode.valueOf(code.getHttpStatus()), request);
    }

    @Override
    protected ResponseEntity<Object> handleAsyncRequestNotUsableException(AsyncRequestNotUsableException error,
                                                                          WebRequest request) {
        log.debug("Client disconnected from asynchronous request");
        return null;
    }

    private ResponseEntity<Object> respond(ErrorCode code, String message, WebRequest request) {
        return respond(code, Result.fail(code, message), new HttpHeaders(), request);
    }

    private ResponseEntity<Object> respond(ErrorCode code, Object body, HttpHeaders headers, WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            HttpServletResponse response = servlet.getResponse();
            if (response != null && response.isCommitted()) {
                return null;
            }
        }
        return ResponseEntity.status(code.getHttpStatus()).headers(headers).body(body);
    }
}
