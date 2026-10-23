package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminMediaRetryController {
    private final MediaRetryPort retries;

    @GetMapping("/media-tasks")
    public Result<CursorPage<Task, Long>> query(@ModelAttribute Query query) {
        actor();
        return Result.success(retries.query(query));
    }

    @PostMapping("/media-retry-batches")
    public ResponseEntity<Result<Admission>> create(@RequestBody Request request) {
        var result = retries.create(request, actor());
        if (result.accepted()) return ResponseEntity.accepted().body(Result.success(result));
        return ResponseEntity.status(ErrorCode.STATE_CONFLICT.getHttpStatus())
                .body(Result.fail(ErrorCode.STATE_CONFLICT, result.message(), result));
    }

    @GetMapping("/media-retry-batches")
    public Result<CursorPage<Batch, Long>> history(@RequestParam(required = false) Long cursor,
                                                  @RequestParam(defaultValue = "20") Integer size) {
        actor();
        return Result.success(retries.history(cursor, size));
    }

    @GetMapping("/media-retry-batches/current")
    public Result<Batch> current() {
        actor();
        return Result.success(retries.current());
    }

    @GetMapping("/media-retry-batches/{id}")
    public Result<Batch> batch(@PathVariable Long id) {
        actor();
        return Result.success(retries.batch(id));
    }

    @GetMapping("/media-retry-batches/{id}/tasks")
    public Result<List<Affected>> affected(@PathVariable Long id) {
        actor();
        return Result.success(retries.affected(id));
    }

    @GetMapping("/media-retry-batches/{id}/items")
    public Result<List<Item>> items(@PathVariable Long id) {
        actor();
        return Result.success(retries.items(id));
    }

    private Long actor() {
        if (!"1".equals(UserContext.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        return 1L;
    }
}
