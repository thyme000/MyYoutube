package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort.*;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorDeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/dead-letters/content-vectors")
@RequiredArgsConstructor
public class InternalContentVectorDeadLetterController {
    private final ContentVectorDeadLetterService service;
    private final InternalApiCredentials credentials;

    @GetMapping("/messages")
    public Page query(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") Integer size,
                      @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return service.query(cursor, size);
    }

    @PostMapping("/messages/replay")
    public ReplayResult replay(@RequestBody Reference reference, @RequestParam Long actorId,
                               @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return service.replay(reference, actorId);
    }
}
