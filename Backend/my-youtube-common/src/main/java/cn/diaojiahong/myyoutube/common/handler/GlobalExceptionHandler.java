package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    // 路由未命中, 不打堆栈
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNoResourceFound(NoResourceFoundException e) {
        return Result.fail(404, "资源不存在");
    }

    /**
     * 客户端断开长连接(SSE/流式响应)后服务端仍在写: 连接已死, 响应无意义, 降噪不打堆栈。
     * 断连是常态, 注册表层会在发送失败后摘除死连接。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public Result<?> handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("客户端断开长连接: {}", e.getMessage());
        return null;
    }

    // 业务异常
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBizException(BizException e) {
        // BIZ_ERROR/USER_ERROR 是校验或状态不满足的业务拒绝, 属预期结果, 记 WARN 且不打堆栈;
        // 其余码(存储/打标/通知/消息/会话)代表外部依赖或落库失败, 才是需要关注的 ERROR。
        if (e.getCode() == BizException.BIZ_ERROR || e.getCode() == BizException.USER_ERROR) {
            log.warn("业务拒绝 code={} message={}", e.getCode(), e.getMessage());
        } else {
            log.error("业务失败 code={} message={}", e.getCode(), e.getMessage(), e);
        }
        return Result.fail(e.getMessage());
    }

    // 业务校验抛出的非法参数(如权限/状态不满足), 按业务错误返回 400 而非 500
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleUnreadableMessage(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败");
        return Result.fail(400, "请求体格式错误或包含不支持的字段值");
    }

    // 处理其他异常类型
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        log.error("异常: {}", e.getMessage(), e);
        return Result.fail("系统繁忙,请稍后再试～");
    }
}
