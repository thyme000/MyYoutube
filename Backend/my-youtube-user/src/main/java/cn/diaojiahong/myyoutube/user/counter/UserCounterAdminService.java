package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.user.mapper.CounterDriftMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 用户侧粉丝数、关注数的漂移清单和立即覆盖。覆盖走现成的 CounterTarget.overwrite。 */
@Service
@RequiredArgsConstructor
public class UserCounterAdminService {

    private final CounterDriftMapper drifts;
    private final CounterFlushService flush;

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

    public int repair(CounterType type, List<Long> ids) {
        requireOwned(type);
        return flush.repair(type, ids);
    }

    private List<CounterDrift> query(CounterType type, long afterId, int limit) {
        return switch (type) {
            case USER_FANS -> drifts.fans(afterId, limit);
            case USER_ATTENTION -> drifts.attentions(afterId, limit);
            default -> throw unknown();
        };
    }

    private void requireOwned(CounterType type) {
        switch (type) {
            case USER_FANS, USER_ATTENTION -> {
            }
            default -> throw unknown();
        }
    }

    private static BizException unknown() {
        return new BizException(ErrorCode.INVALID_ARGUMENT, "本服务不处理该计数类型");
    }
}
