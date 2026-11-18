package cn.diaojiahong.myyoutube.content.report;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 播放会话发号。同一账号在固定 TTL 内共用一个会话，过期后下一次上报生成新会话。
 */
@Component
@RequiredArgsConstructor
public class PlaybackReportStore {

    private static final RedisScript<String> SESSION_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/playback-session.lua"), String.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 取或建当前播放会话: 固定 TTL 不续期, 后续观看加入现有会话, 过期后下一次上报生成新会话。
     * 会话 id 是行为流水与播放量的分组原语, 会话期即播放量窗口。
     */
    public String sessionFor(long userId) {
        return redisTemplate.execute(SESSION_SCRIPT,
                List.of(RedisConstants.PLAYBACK_REPORT_SESSION + userId),
                UUID.randomUUID().toString(), String.valueOf(RedisConstants.PLAYBACK_REPORT_SESSION_TTL_SECONDS));
    }
}
