package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UserPort 本地实现
 */
@Component
@RequiredArgsConstructor
public class UserPortAdapter implements UserPort {

    private final cn.diaojiahong.myyoutube.user.service.IUserService userService;
    private final cn.diaojiahong.myyoutube.user.mapper.UserCounterEventMapper counterEvents;
    private final CounterBuffer counterBuffer;
    private final UserSummaryCache summaryCache;

    @Override
    public UserSummary getUser(Long id) {
        return summaryCache.get(id, uid -> {
            User user = userService.getById(uid);
            return user == null ? null : toSummary(user);
        });
    }

    @Override
    public List<UserSummary> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return summaryCache.listByIds(ids, missIds -> userService.listByIds(missIds).stream()
                .map(UserPortAdapter::toSummary).collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public void applyContentCountEvent(cn.diaojiahong.myyoutube.user.api.dto.ContentCountEvent event) {
        if (event.eventId() == null || event.eventId().isBlank() || event.userId() == null || Math.abs(event.delta()) != 1)
            throw new IllegalArgumentException("非法作品计数事件");
        if (counterEvents.record(event.eventId(), event.userId()) > 0) {
            if (counterEvents.applyDelta(event.userId(), event.delta()) != 1)
                throw new IllegalStateException("作品计数事件无法应用");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { summaryCache.evict(event.userId()); }
                });
    }

    @Override
    public void adjustCounters(List<CounterAdjust> adjustments) {
        for (CounterAdjust a : adjustments) {
            switch (a.getField()) {
                // 粉丝数/关注数只累加到 Redis, 由 user 模块的落库任务合并成一次 UPDATE。
                // 大 V 的单行竞争极高, 而这两个字段本来就不是同步可见的
                case "fans" -> counterBuffer.add(CounterType.USER_FANS, a.getUserId(), a.getDelta());
                case "attentionCount" -> counterBuffer.add(CounterType.USER_ATTENTION, a.getUserId(), a.getDelta());
                default -> throw new IllegalArgumentException("未知计数字段: " + a.getField());
            }
        }
    }

    private static UserSummary toSummary(User user) {
        UserSummary s = new UserSummary();
        BeanUtil.copyProperties(user, s);
        return s;
    }
}
