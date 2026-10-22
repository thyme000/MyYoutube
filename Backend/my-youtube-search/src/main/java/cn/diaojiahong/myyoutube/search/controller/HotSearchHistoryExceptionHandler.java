package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {HotSearchHistoryController.class, InternalHotSearchHistoryController.class})
public class HotSearchHistoryExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Result.fail(400, error.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> binding() {
        return ResponseEntity.badRequest().body(Result.fail(400, "历史查询参数无效"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<?>> unavailable(DataAccessException error) {
        log.warn("Hot search history query unavailable errorType={}", error.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Result.fail(503, "热搜历史暂时不可用"));
    }
}
