package cn.diaojiahong.myyoutube.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.like.counter.AuthorLikeCountService;
import cn.diaojiahong.myyoutube.video.mapper.CounterDriftMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 作品侧计数的漂移清单和立即覆盖。覆盖走各 CounterTarget 现成的 overwrite。
 * 作品赞差额会进作者行，这里接着把已抬高的 version 抄到 user，不等下一轮定时任务。
 */
@Service
@RequiredArgsConstructor
public class VideoCounterAdminService {

    private final CounterDriftMapper drifts;
    private final CounterFlushService flush;
    private final AuthorLikeCountService authorLikeCounts;

    public CursorPage<CounterDrift, Long> drifts(CounterType type, Long cursor, int size) {
        PageQuery.validatePageSize(size);
        long afterId = afterId(cursor);
        List<CounterDrift> fetched = query(type, afterId, CursorPage.fetchSize(size));
        return CursorPage.slice(fetched, size, CounterDrift::getId);
    }

    private static long afterId(Long cursor) {
        if (cursor == null) {
            return 0L;
        }
        if (cursor < 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "游标无效");
        }
        return cursor;
    }

    /**
     * 每条单独提交。中途失败时，前面的 id 可能已经写上，调用方仍收到错误。
     * finally 仍会抄作者获赞，一轮最多 200 人，按作者编号排队；没排上的等后续定时任务。
     */
    public int repair(CounterType type, List<Long> ids) {
        requireOwned(type);
        try {
            return flush.repair(type, ids);
        } finally {
            authorLikeCounts.pushPending();
        }
    }

    private List<CounterDrift> query(CounterType type, long afterId, int limit) {
        return switch (type) {
            case CONTENT_LIKE -> drifts.contentLikes(afterId, limit);
            case COMMENT_LIKE -> drifts.commentLikes(afterId, limit);
            case CONTENT_COLLECT -> drifts.collects(afterId, limit);
            case CONTENT_SHARE -> drifts.shares(afterId, limit);
            default -> throw unknown();
        };
    }

    private void requireOwned(CounterType type) {
        switch (type) {
            case CONTENT_LIKE, COMMENT_LIKE, CONTENT_COLLECT, CONTENT_SHARE -> {
            }
            default -> throw unknown();
        }
    }

    private static BizException unknown() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "本服务不处理该计数类型");
    }
}
