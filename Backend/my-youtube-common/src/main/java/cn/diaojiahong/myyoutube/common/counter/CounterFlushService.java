package cn.diaojiahong.myyoutube.common.counter;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * 管理端按指定 id 立刻覆盖。和小时对账同一条写路径：先丢掉 Redis 里尚未落库的增量，
     * 再用权威值覆盖。不扫 dirty 集合，因此分享数这类不进小时对账的类型也能修。
     *
     * @return 实际执行了覆盖的条数。没有权威值的 id 只清 dirty，不计入
     */
    public int repair(CounterType type, List<Long> ids) {
        List<Long> requested = repairIds(ids);
        CounterTarget target = find(type);
        int repaired = 0;
        for (Long id : requested) {
            if (applyTruth(target, id)) {
                repaired++;
            }
        }
        return repaired;
    }

    private void flushTarget(CounterTarget target) {
        CounterType type = target.type();
        // inflight 排前面：回补属于正确性，不能被本轮 pending 的量顶掉
        Set<Long> ids = new LinkedHashSet<>(counterBuffer.scanIds(CounterKeys.inflightPrefix(type)));
        ids.addAll(counterBuffer.scanIds(CounterKeys.pendingPrefix(type)));
        Map<Long, Long> ackedDeltas = new LinkedHashMap<>();
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
            } catch (Exception e) {
                log.warn("计数落库失败, 下轮回补重放, type={}, id={}, delta={}", type, id, delta, e);
                continue;
            }
            counterBuffer.ack(type, id);
            ackedDeltas.merge(id, delta, Long::sum);
        }
        if (!ackedDeltas.isEmpty()) {
            try {
                target.afterFlush(ackedDeltas);
            } catch (Exception e) {
                log.warn("计数旁路同步失败(不影响落库), type={}, ids={}", type, ackedDeltas.keySet(), e);
            }
        }
    }

    private void reconcileOne(CounterTarget target, long id) {
        try {
            applyTruth(target, id);
        } catch (Exception e) {
            log.warn("计数对账失败, 下轮重试, type={}, id={}", target.type(), id, e);
        }
    }

    /** @return 是否用权威值覆盖了计数列 */
    private boolean applyTruth(CounterTarget target, long id) {
        CounterType type = target.type();
        // 先丢弃 pending：权威值来自同步写入的关系表, 已经含了这部分增量
        counterBuffer.discard(type, id);
        Long truth = target.authoritativeValue(id);
        if (truth == null) {
            counterBuffer.clearDirty(type, id);
            return false;
        }
        target.overwrite(id, truth);
        counterBuffer.clearDirty(type, id);
        return true;
    }

    private CounterTarget find(CounterType type) {
        for (CounterTarget target : targets.stream().toList()) {
            if (target.type() == type) {
                return target;
            }
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "本服务不处理该计数类型");
    }

    private static List<Long> repairIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请选择要覆盖的计数");
        }
        if (ids.size() > PageQuery.MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "单次最多覆盖 " + PageQuery.MAX_PAGE_SIZE + " 条");
        }

        List<Long> distinct = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id < 1) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "计数 id 无效");
            }
            if (!distinct.contains(id)) {
                distinct.add(id);
            }
        }
        return distinct;
    }
}
