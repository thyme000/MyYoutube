package cn.diaojiahong.myyoutube.common.counter;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计数落库轮与对账轮。正确性由 {@link CounterBuffer#claim} 的 Lua 原子认领保证，
 * 锁只用来避免两个实例同时做无用功（见 docs/计数落库改造设计.md §6）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterFlushService {

    private final CounterBuffer counterBuffer;
    private final ObjectProvider<CounterTarget> targets;

    /** 落库轮：回补 inflight + 认领 pending + 落库 + ack。任一步抛错都不 ack，留给下轮重放 */
    public void flushRound() {
        List<CounterTarget> targetList = targets.stream().toList();
        for (CounterTarget target : targetList) {
            flushTarget(target);
        }
    }

    /** 对账轮：用 COUNT(*) 无条件覆盖，抹平任意方向的累积误差 */
    public void reconcileRound() {
        for (CounterTarget target : targets.stream().toList()) {
            if (!target.type().reconcile()) {
                continue;
            }
            for (Long id : counterBuffer.dirtyIds(target.type())) {
                reconcileOne(target, id);
            }
        }
    }

    private void flushTarget(CounterTarget target) {
        CounterType type = target.type();
        // inflight 排前面：回补属于正确性，不能被本轮 pending 的量顶掉
        Set<Long> ids = new LinkedHashSet<>(counterBuffer.scanIds(CounterKeys.inflightPrefix(type)));
        ids.addAll(counterBuffer.scanIds(CounterKeys.pendingPrefix(type)));
        int processed = 0;
        for (Long id : ids) {
            if (processed >= RedisConstants.COUNTER_MAX_KEYS_PER_ROUND) {
                log.warn("计数落库单轮达上限, 剩余 id 下轮处理, type={}, limit={}", type, RedisConstants.COUNTER_MAX_KEYS_PER_ROUND);
                break;
            }
            processed++;
            Long delta = counterBuffer.claim(type, id);
            if (delta == null) {
                continue;
            }
            try {
                target.applyDelta(id, delta);
                counterBuffer.ack(type, id);
            } catch (Exception e) {
                log.warn("计数落库失败, 下轮回补重放, type={}, id={}, delta={}", type, id, delta, e);
            }
        }
    }

    private void reconcileOne(CounterTarget target, long id) {
        CounterType type = target.type();
        // 先丢弃 pending：权威值来自同步写入的关系表, 已经含了这部分增量
        counterBuffer.discard(type, id);
        try {
            Long truth = target.authoritativeValue(id);
            if (truth == null) {
                counterBuffer.clearDirty(type, id);
                return;
            }
            target.overwrite(id, truth);
            counterBuffer.clearDirty(type, id);
        } catch (Exception e) {
            log.warn("计数对账失败, 下轮重试, type={}, id={}", type, id, e);
        }
    }
}
