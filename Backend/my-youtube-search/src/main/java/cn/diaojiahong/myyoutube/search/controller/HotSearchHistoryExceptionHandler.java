package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {HotSearchHistoryController.class, InternalHotSearchHistoryController.class})
public class HotSearchHistoryExceptionHandler {
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Result<?>> unavailable(DataAccessResourceFailureException error) {
        log.error("Hot search history storage unavailable", error);
        return ResponseEntity.status(ErrorCode.DEPENDENCY_UNAVAILABLE.getHttpStatus())
                .body(Result.fail(ErrorCode.DEPENDENCY_UNAVAILABLE, "热搜历史暂时不可用"));
    }
}
