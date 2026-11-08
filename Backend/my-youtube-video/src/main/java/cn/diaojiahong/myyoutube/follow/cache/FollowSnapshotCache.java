package cn.diaojiahong.myyoutube.follow.cache;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 关注快照读侧缓存: viewer 的关注 id 集合, 固定短 TTL 过期。
 * user 写路径的删除只是尽力而为的提速, 滞后上界始终由 TTL 保证, 读路径不得依赖删除成功。
 */
@Component
public class FollowSnapshotCache {

    private static final String SEPARATOR = ",";

    private final FollowPort followPort;
    private final StringRedisTemplate redis;

    @Value("${my-youtube.follow.snapshot-ttl-seconds:5}")
    private long ttlSeconds;

    public FollowSnapshotCache(FollowPort followPort, StringRedisTemplate redis) {
        this.followPort = followPort;
        this.redis = redis;
    }

    /** 空列表存空串(命中即空), 与"键不存在=未建缓存"区分开 */
    public List<Long> getFollowIds(Long userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value != null) {
            return decode(value);
        }

        List<Long> ids = followPort.getFollowIds(userId);
        redis.opsForValue().set(key(userId), encode(ids), Duration.ofSeconds(ttlSeconds));
        return ids;
    }

    public List<Boolean> isFollowOnList(Long userId, List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return List.of();
        }

        Set<Long> followed = new HashSet<>(getFollowIds(userId));
        return authorIds.stream().map(followed::contains).toList();
    }

    private static String key(Long userId) {
        return RedisConstants.FOLLOW_SNAPSHOT + userId;
    }

    private static String encode(List<Long> ids) {
        StringBuilder joined = new StringBuilder();
        for (Long id : ids) {
            if (joined.length() > 0) {
                joined.append(SEPARATOR);
            }
            joined.append(id);
        }
        return joined.toString();
    }

    private static List<Long> decode(String value) {
        if (value.isEmpty()) {
            return List.of();
        }

        String[] parts = value.split(SEPARATOR);
        List<Long> ids = new ArrayList<>(parts.length);
        for (String part : parts) {
            ids.add(Long.parseLong(part));
        }
        return ids;
    }
}
