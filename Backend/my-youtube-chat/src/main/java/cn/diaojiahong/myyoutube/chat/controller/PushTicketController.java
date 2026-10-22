package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * 长连接(WS/SSE)连接票签发: 前端用 Bearer 换短时效一次性票, 再以 ?ticket=xxx 发起连接。
 * 票由网关 GETDEL 原子消费, URL 上不再出现长时效 token。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat/push")
public class PushTicketController {

    public static final long TICKET_TTL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;

    @PostMapping("/ticket")
    public Result<String> issueTicket() {
        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        String ticket = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(RedisConstants.PUSH_CONNECTION_TICKET + ticket,
                userId, Duration.ofSeconds(TICKET_TTL_SECONDS));
        return Result.success(ticket);
    }
}
