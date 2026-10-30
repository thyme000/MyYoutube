package cn.diaojiahong.myyoutube.recommend.tag;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签词库池: 每类目一个 ZSET(member=slug, score=引用数, 热度降序), 通用池 key=common;
 * slug→元数据存 Hash。引用数由打标链路事件式增减, 定时任务只对账成员(增补新词/回收下线词),
 * 全量重建(热度校正)走回刷接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagPoolHolder {

    private static final String COMMON = "common";
    private static final String META_SEP = "|";

    private final ITagService tagService;
    private final StringRedisTemplate redisTemplate;

    /** 成员对账: DB 启用标签缺的补进池(热度取全局引用计数), 池里多余的(已下线)清掉 */
    @Scheduled(initialDelay = 10_000L, fixedDelay = 600_000L)
    public void sync() {
        List<Tag> active = tagService.lambdaQuery().eq(Tag::getStatus, 1).list();
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(RedisConstants.TAG_POOL_META);

        Set<String> activeSlugs = new HashSet<>(active.size());
        int added = 0;
        for (Tag tag : active) {
            activeSlugs.add(tag.getSlug());
            if (!meta.containsKey(tag.getSlug())) {
                register(tag, currentRefScore(tag));
                added++;
            }
        }
        int removed = 0;
        for (Map.Entry<Object, Object> e : meta.entrySet()) {
            String slug = e.getKey().toString();
            if (!activeSlugs.contains(slug)) {
                Tag stale = fromMetaValue(slug, e.getValue().toString());
                if (stale != null) {
                    removeFromPool(stale);
                    removed++;
                }
            }
        }
        if (added > 0 || removed > 0) {
            log.info("词库池对账完成: 补入={}, 清除={}", added, removed);
        }
    }

    /** 新词入池: 元数据必写(否则窗口期重复建词), 热度分由打标链路 ZINCRBY 继续抬 */
    public void register(Tag tag, double score) {
        redisTemplate.opsForHash().put(RedisConstants.TAG_POOL_META, tag.getSlug(), metaValue(tag));
        redisTemplate.opsForZSet().add(poolKey(tag.getCategoryCode()), tag.getSlug(), score);
    }

    public void removeFromPool(Tag tag) {
        redisTemplate.opsForHash().delete(RedisConstants.TAG_POOL_META, tag.getSlug());
        redisTemplate.opsForZSet().remove(poolKey(tag.getCategoryCode()), tag.getSlug());
    }

    /** 引用计数双写: 全局 ZSET(tagId 计数) + 类目池 ZSET(slug 热度) */
    public void incrRefCount(Tag tag, double delta) {
        redisTemplate.opsForZSet().incrementScore(RedisConstants.CONTENT_TAG_REF, tag.getId().toString(), delta);
        redisTemplate.opsForZSet().incrementScore(poolKey(tag.getCategoryCode()), tag.getSlug(), delta);
    }

    /** 类目/通用 → 词库, 热度降序; key 为空串表示通用池 */
    public Map<String, List<Tag>> grouped() {
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(RedisConstants.TAG_POOL_META);
        Map<String, List<Tag>> result = new LinkedHashMap<>();
        for (VideoCategoryEnum c : VideoCategoryEnum.values()) {
            readPool(c.getCode(), meta, result);
        }
        readPool(COMMON, meta, result);
        return result;
    }

    public Tag findBySlug(String slug) {
        Object value = redisTemplate.opsForHash().get(RedisConstants.TAG_POOL_META, slug);
        return value == null ? null : fromMetaValue(slug, value.toString());
    }

    /** 回刷用全量重建: 以 DB 实况为准重写池与元数据, 顺带校正热度漂移 */
    public void rebuildAll(List<Tag> active, Map<Long, Long> refCounts) {
        redisTemplate.delete(RedisConstants.TAG_POOL_META);
        redisTemplate.delete(poolKey(COMMON));
        for (VideoCategoryEnum c : VideoCategoryEnum.values()) {
            redisTemplate.delete(poolKey(c.getCode()));
        }
        for (Tag tag : active) {
            register(tag, refCounts.getOrDefault(tag.getId(), 0L));
        }
    }

    private void readPool(String category, Map<Object, Object> meta, Map<String, List<Tag>> result) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(poolKey(category), 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return;
        }

        List<Tag> tags = tuples.stream()
                .map(t -> fromMetaValue(t.getValue(), toStringValue(meta.get(t.getValue()))))
                .filter(java.util.Objects::nonNull)
                .toList();
        result.put(COMMON.equals(category) ? "" : category, tags);
    }

    private double currentRefScore(Tag tag) {
        Double score = redisTemplate.opsForZSet().score(RedisConstants.CONTENT_TAG_REF, tag.getId().toString());
        return score == null ? 0 : score;
    }

    private String poolKey(String categoryCode) {
        return RedisConstants.TAG_POOL + (categoryCode == null || categoryCode.isBlank() ? COMMON : categoryCode);
    }

    private String metaValue(Tag tag) {
        return tag.getId() + META_SEP + tag.getName() + META_SEP
                + (tag.getCategoryCode() == null ? "" : tag.getCategoryCode());
    }

    private Tag fromMetaValue(String slug, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.split("\\" + META_SEP, -1);
        if (parts.length < 3) {
            return null;
        }

        try {
            return new Tag()
                    .setId(Long.parseLong(parts[0]))
                    .setName(parts[1])
                    .setSlug(slug)
                    .setCategoryCode(parts[2].isBlank() ? null : parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toStringValue(Object v) {
        return v == null ? null : v.toString();
    }
}
