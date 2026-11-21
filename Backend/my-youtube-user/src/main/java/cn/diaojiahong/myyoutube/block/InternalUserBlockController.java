package cn.diaojiahong.myyoutube.block;

import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/user-block")
@RequiredArgsConstructor
public class InternalUserBlockController {
    private final UserBlockService blocks;
    private final InternalApiCredentials credentials;

    @GetMapping("/authors")
    public List<Long> blockedAuthors(
            @RequestParam Long viewerId,
            @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return blocks.blockedAuthors(viewerId);
    }

    @GetMapping("/private-chat")
    public boolean blocksPrivateChat(
            @RequestParam Long userId, @RequestParam Long peerId,
            @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return blocks.blocksPrivateChat(userId, peerId);
    }
}
