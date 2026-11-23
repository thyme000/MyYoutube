package cn.diaojiahong.myyoutube.comment.cache;

import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.comment.cursor.CommentCursor;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentTab;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentListCacheImpl implements CommentListCache {

    /** 名单值就是「排序值.评论id」按榜序逗号相连, 与游标同一套编码 */
    private static final String SEPARATOR = ",";
    private static final long BUILD_LOCK_SECONDS = 10;

    private final StringRedisTemplate redis;
    // 直接依赖 mapper 而非 service: service 实现反过来依赖本缓存, 走 service 会成环
    private final ContentCommentMapper commentMapper;

    @Value("${my-youtube.comment.cache-threshold:50}")
    private int threshold;

    @Value("${my-youtube.comment.latest-cap:200}")
    private int latestCap;

    @Value("${my-youtube.comment.hot-cap:100}")
    private int hotCap;

    @Value("${my-youtube.comment.latest-ttl-seconds:5}")
    private long latestTtlSeconds;

    @Value("${my-youtube.comment.hot-ttl-seconds:5}")
    private long hotTtlSeconds;

    @Override
    public List<CommentCursor> get(Long contentId, CommentTab tab) {
        String key = key(contentId, tab);
        try {
            String value = redis.opsForValue().get(key);
            if (value == null || value.isEmpty()) {
                return null;
            }

            List<CommentCursor> cursors = new ArrayList<>();
            for (String item : value.split(SEPARATOR)) {
                cursors.add(CommentCursor.decode(item));
            }
            return cursors;
        } catch (RuntimeException e) {
            // 缓存层故障或名单损坏都退化为直查; 名单损坏时顺手清掉, 下次重建
            log.warn("评论名单缓存读取失败, 本次走 DB contentId={} tab={}", contentId, tab, e);
            deleteQuietly(key);
            return null;
        }
    }

    @Override
    public void build(Long contentId, CommentTab tab, long commentCount) {
        if (commentCount < threshold) {
            return;
        }

        String key = key(contentId, tab);
        String lockKey = lockKey(contentId, tab);
        try {
            // 抢不到锁的这轮直接走 DB, 不等待
            if (!Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofSeconds(BUILD_LOCK_SECONDS)))) {
                return;
            }
            // 进锁后核实: 抢锁前看到的"缓存缺失"可能已过期
            if (redis.hasKey(key)) {
                return;
            }
            List<CommentCursor> cursors = queryOrderedCursors(contentId, tab);
            if (cursors.isEmpty()) {
                return;
            }
            String value = cursors.stream().map(CommentCursor::encode).collect(Collectors.joining(SEPARATOR));
            // 固定 TTL 不续期: 续期会让持续被读的热内容永不刷新
            Duration ttl = tab == CommentTab.LATEST
                    ? Duration.ofSeconds(latestTtlSeconds) : Duration.ofSeconds(hotTtlSeconds);
            // 写榜与放锁互相独立, pipeline 一次往返; pipeline 故障时锁不再显式释放, 由 10s TTL 兜底自愈
            redis.executePipelined((RedisCallback<Object>) connection -> {
                connection.stringCommands().set(
                    Objects.requireNonNull(StringRedisSerializer.UTF_8.serialize(key)),
                    Objects.requireNonNull(StringRedisSerializer.UTF_8.serialize(value)),
                        Expiration.from(ttl), SetOption.UPSERT);
                connection.keyCommands().del(StringRedisSerializer.UTF_8.serialize(lockKey));
                return null;
            });
        } catch (RuntimeException e) {
            // 缓存层故障不阻断读路径, 本次退化为直查, 下轮再建
            log.warn("评论名单缓存构建失败 contentId={} tab={}", contentId, tab, e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("评论名单缓存清理失败 key={}", key, e);
        }
    }

    private List<CommentCursor> queryOrderedCursors(Long contentId, CommentTab tab) {
        boolean latest = tab == CommentTab.LATEST;
        LambdaQueryWrapper<ContentComment> w = new LambdaQueryWrapper<ContentComment>()
                .eq(ContentComment::getContentId, contentId)
                .eq(ContentComment::getLevel, 1)
                .in(ContentComment::getStatus,
                    CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode());
        if (latest) {
            w.orderByDesc(ContentComment::getCreateTime).orderByDesc(ContentComment::getId);
        } else {
            w.orderByDesc(ContentComment::getLikeCount).orderByDesc(ContentComment::getId);
        }
        w.last("LIMIT " + (latest ? latestCap : hotCap));
        return commentMapper.selectList(w).stream()
                .map(c -> latest
                        ? CommentCursor.of(epochSecond(c.getCreateTime()), c.getId())
                        : CommentCursor.of(c.getLikeCount() == null ? 0 : c.getLikeCount(), c.getId()))
                .toList();
    }

    private static String key(Long contentId, CommentTab tab) {
        return "comment:list:" + tab.getCode() + ":" + contentId;
    }

    private static String lockKey(Long contentId, CommentTab tab) {
        return "comment:list:lock:" + tab.getCode() + ":" + contentId;
    }

    private static long epochSecond(LocalDateTime time) {
        return time == null ? 0 : BusinessTime.toInstant(time).getEpochSecond();
    }
}
