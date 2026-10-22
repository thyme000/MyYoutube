package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminMediaRetryController {
    private final MediaRetryPort retries;
    private Long actor() {
        if (!"1".equals(UserContext.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        return Long.valueOf(UserContext.getUserId());
    }
    @GetMapping("/media-tasks") public Page query(@ModelAttribute Query query) { actor(); return retries.query(query); }
    @PostMapping("/media-retry-batches") public ResponseEntity<Admission> create(@RequestBody Request request) {
        var result=retries.create(request,actor());
        return ResponseEntity.status(result.accepted() ? 202 : 409).body(result);
    }
    @GetMapping("/media-retry-batches") public BatchPage history(@RequestParam(required=false) Long cursor,
            @RequestParam(defaultValue="20") Integer size) { actor(); return retries.history(cursor,size); }
    @GetMapping("/media-retry-batches/current") public Batch current() { actor(); return retries.current(); }
    @GetMapping("/media-retry-batches/{id}") public Batch batch(@PathVariable Long id) { actor(); return retries.batch(id); }
    @GetMapping("/media-retry-batches/{id}/tasks") public List<Affected> affected(@PathVariable Long id) { actor(); return retries.affected(id); }
    @GetMapping("/media-retry-batches/{id}/items") public List<Item> items(@PathVariable Long id) { actor(); return retries.items(id); }
}
