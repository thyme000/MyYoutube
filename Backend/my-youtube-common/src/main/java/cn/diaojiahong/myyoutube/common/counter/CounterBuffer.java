package cn.diaojiahong.myyoutube.common.counter;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计数缓冲的 Redis 侧读写。写路径与落库路径各一个 Lua 脚本，把多次往返压成一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterBuffer {

    /** 写路径：累积增量 + 登记待对账 id。*/
    private static final RedisScript<Long> ADD_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/counter-add.lua"), Long.class);

    /**
     * 落库路径：回补上一轮已认领但未确认的 inflight，再整体认领本轮 pending。
     */
    private static final RedisScript<String> CLAIM_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/counter-claim.lua"), String.class);

    private static final int SCAN_COUNT = 500;

    private final StringRedisTemplate redisTemplate;

    /** 累加增量。不设 TTL：每轮都被认领并 DEL，不会堆积 */
    public void add(CounterType type, long id, long delta) {
        if (delta == 0) {
            return;
        }
        if (!type.reconcile()) {
            redisTemplate.opsForValue().increment(CounterKeys.pending(type, id), delta);
            return;
        }
        redisTemplate.execute(ADD_SCRIPT,
                List.of(CounterKeys.pending(type, id), CounterKeys.dirty(type)),
                String.valueOf(delta), String.valueOf(id),
                String.valueOf(RedisConstants.COUNTER_DIRTY_TTL_SECONDS));
    }

    /** 认领：返回增量，无增量返回 null */
    public Long claim(CounterType type, long id) {
        String claimed = redisTemplate.execute(CLAIM_SCRIPT,
                List.of(CounterKeys.pending(type, id), CounterKeys.inflight(type, id)),
                String.valueOf(RedisConstants.COUNTER_INFLIGHT_TTL_SECONDS));
        if (claimed == null) {
            return null;
        }
        try {
            return Long.valueOf(claimed);
        } catch (NumberFormatException e) {
            log.warn("计数缓冲值非法, type={}, id={}, value={}", type, id, claimed);
            return null;
        }
    }

    /** 确认落库成功。崩溃在这一步之前，下轮由 CLAIM_SCRIPT 的回补分支重放 */
    public void ack(CounterType type, long id) {
        redisTemplate.delete(CounterKeys.inflight(type, id));
    }

    /**
     * 认领后直接丢弃。对账轮专用：点赞关系是同步 insert 的，COUNT(*) 天然已包含 pending 里那部分增量，
     * 再叠加一次就是重复计数。
     */
    public void discard(CounterType type, long id) {
        claim(type, id);
        ack(type, id);
    }

    /** SCAN 出某前缀下的全部 id。成本随 keyspace 总量增长，调用方需自行设上限 */
    public Set<Long> scanIds(String keyPrefix) {
        Set<Long> ids = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(keyPrefix + "*").count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                try {
                    ids.add(Long.valueOf(key.substring(keyPrefix.length())));
                } catch (NumberFormatException e) {
                    log.warn("计数缓冲 key 非法, key={}", key);
                }
            }
        }
        return ids;
    }

    /** 最近被写过的 id，对账范围由写入痕迹决定：不写入就不会新增漂移 */
    public Set<Long> dirtyIds(CounterType type) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> members = redisTemplate.opsForSet().members(CounterKeys.dirty(type));
        if (members == null) {
            return ids;
        }
        for (String member : members) {
            try {
                ids.add(Long.valueOf(member));
            } catch (NumberFormatException e) {
                log.warn("dirty 集合成员非法, type={}, member={}", type, member);
            }
        }
        return ids;
    }

    public void clearDirty(CounterType type, long id) {
        redisTemplate.opsForSet().remove(CounterKeys.dirty(type), String.valueOf(id));
    }
}
