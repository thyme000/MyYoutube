package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/dead-letters/content-vectors")
@RequiredArgsConstructor
public class AdminContentVectorDeadLetterController {
    private final ContentVectorDeadLetterPort port;

    @GetMapping("/messages")
    public Page query(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") Integer size) {
        actor();
        return port.query(cursor, size);
    }

    @PostMapping("/messages/replay")
    public ResponseEntity<ReplayResult> replay(@RequestBody Reference reference) {
        ReplayResult result = port.replay(reference, actor());
        int status = switch (result.status()) {
            case ACCEPTED -> 202;
            case ALREADY_COMPLETED -> 200;
            case INELIGIBLE, DISABLED -> 409;
            case NOT_FOUND -> 404;
            case INVALID_MESSAGE -> 400;
            case STATE_UNAVAILABLE, DELIVERY_UNCONFIRMED -> 503;
        };
        return ResponseEntity.status(status).body(result);
    }

    private Long actor() {
        if (!"1".equals(UserContext.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        return 1L;
    }

}
