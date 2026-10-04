package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    // 处理业务异常
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBizException(BizException e) {
        log.error("业务异常: {}", e.getMessage(), e);
        return Result.fail(e.getMessage());
    }

    // 业务校验抛出的非法参数(如权限/状态不满足), 按业务错误返回 400 而非 500
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    // 处理其他异常类型
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
    log.error("异常: {}", e.getMessage(), e);
        return Result.fail("系统繁忙,请稍后再试～");
    }
}
