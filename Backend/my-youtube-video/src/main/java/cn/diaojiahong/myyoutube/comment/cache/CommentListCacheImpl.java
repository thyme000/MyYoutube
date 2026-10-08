package cn.diaojiahong.myyoutube.comment.cache;

import cn.diaojiahong.myyoutube.comment.cursor.CommentCursor;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentTab;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    @Value("${my-youtube.comment.hot-ttl-seconds:30}")
    private long hotTtlSeconds;

    @Override
    public List<CommentCursor> get(Long contentId, CommentTab tab) {
        String key = key(contentId, tab);
        String value = redis.opsForValue().get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            List<CommentCursor> cursors = new ArrayList<>();
            for (String item : value.split(SEPARATOR)) {
                cursors.add(CommentCursor.decode(item));
            }
            return cursors;
        } catch (RuntimeException e) {
            // 名单损坏就当没缓存, 删掉换下次重建
            redis.delete(key);
            return null;
        }
    }

    @Override
    public void build(Long contentId, CommentTab tab, long commentCount) {
        if (commentCount < threshold) {
            return;
        }
        String key = key(contentId, tab);
        if (redis.hasKey(key)) {
            return;
        }
        String lockKey = RedisConstants.COMMENT_LIST_LOCK + tab.getCode() + ":" + contentId;
        // 抢不到锁的这轮直接走 DB, 不等待
        if (!Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(BUILD_LOCK_SECONDS)))) {
            return;
        }
        try {
            if (redis.hasKey(key)) {
                return;
            }
            List<CommentCursor> cursors = queryOrderedCursors(contentId, tab);
            if (cursors.isEmpty()) {
                return;
            }
            String value = cursors.stream().map(CommentCursor::encode).collect(Collectors.joining(SEPARATOR));
            // 固定 TTL 不续期: 续期会让持续被读的热内容永不刷新
            redis.opsForValue().set(key, value, Duration.ofSeconds(
                    tab == CommentTab.LATEST ? latestTtlSeconds : hotTtlSeconds));
        } finally {
            redis.delete(lockKey);
        }
    }

    private List<CommentCursor> queryOrderedCursors(Long contentId, CommentTab tab) {
        boolean latest = tab == CommentTab.LATEST;
        LambdaQueryWrapper<ContentComment> w = new LambdaQueryWrapper<ContentComment>()
                .eq(ContentComment::getContentId, contentId)
                .eq(ContentComment::getLevel, 1)
                .in(ContentComment::getStatus, CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode());
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
        return RedisConstants.COMMENT_LIST + tab.getCode() + ":" + contentId;
    }

    private static long epochSecond(LocalDateTime time) {
        return time == null ? 0 : time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
