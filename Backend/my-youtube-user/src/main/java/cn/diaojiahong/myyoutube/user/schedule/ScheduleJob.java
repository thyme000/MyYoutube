package cn.diaojiahong.myyoutube.user.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.search.api.UserDocPort;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * user 域定时任务: 获赞量落库(Redis 缓冲 → MySQL + ES)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private final StringRedisTemplate redisTemplate;
    private final IUserService userService;
    private final UserDocPort userDocPort;

    /**
     * 每10分钟执行一次, 把 Redis 缓冲的获赞量刷进 MySQL 与 ES
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void updateUserLikeCount() {
        ScanOptions options = ScanOptions.scanOptions().match(RedisConstants.USER_LIKE_COUNT + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String userId = key.split(RedisConstants.USER_LIKE_COUNT)[1];
                String s = redisTemplate.opsForValue().get(key);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                Long likeCountLong = Long.parseLong(s);
                userService.lambdaUpdate()
                        .setIncrBy(User::getLikeCount, likeCountLong)
                        .eq(User::getId, userId)
                        .update();
                // 同步 ES 中用户获赞量
                userDocPort.adjustLikeCount(Long.parseLong(userId), likeCountLong);
                redisTemplate.delete(key);
            }
        }
    }
}
