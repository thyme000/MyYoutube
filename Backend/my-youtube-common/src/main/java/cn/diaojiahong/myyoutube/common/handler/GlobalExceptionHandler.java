package cn.diaojiahong.myyoutube.common.handler;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 处理业务异常
    @ExceptionHandler(BizException.class)
    public Result<?> handleBizException(BizException e) {
        log.error("业务异常: {}", e.getMessage(), e);
        return Result.fail(e.getMessage());
    }

    // 处理其他异常类型
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
    log.error("异常: {}", e.getMessage(), e);
        return Result.fail("系统繁忙,请稍后再试～");
    }
}
