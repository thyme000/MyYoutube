package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
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
    private final AdminAuthorization authorization;

    @GetMapping("/messages")
    public Result<Page> query(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer size) {
        authorization.requireAdmin();

        return Result.success(port.query(cursor, size));
    }

    @PostMapping("/messages/replay")
    public ResponseEntity<Result<ReplayResult>> replay(@RequestBody Reference reference) {
        ReplayResult result = port.replay(reference, authorization.requireAdmin());
        if (result.status() == ReplayStatus.ACCEPTED) return ResponseEntity.accepted().body(Result.success(result));
        if (result.status() == ReplayStatus.ALREADY_COMPLETED) return ResponseEntity.ok(Result.success(result));
        ErrorCode error = switch (result.status()) {
            case INELIGIBLE, DISABLED -> ErrorCode.STATE_CONFLICT;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case INVALID_MESSAGE -> ErrorCode.INVALID_ARGUMENT;
            case STATE_UNAVAILABLE, DELIVERY_UNCONFIRMED -> ErrorCode.DEPENDENCY_UNAVAILABLE;
            default -> throw new IllegalStateException("Unexpected replay status: " + result.status());
        };
        return ResponseEntity.status(error.getHttpStatus()).body(Result.fail(error, result.message(), result));
    }


}
