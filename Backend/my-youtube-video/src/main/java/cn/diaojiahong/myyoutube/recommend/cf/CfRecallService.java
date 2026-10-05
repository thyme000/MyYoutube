package cn.diaojiahong.myyoutube.recommend.cf;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import cn.diaojiahong.myyoutube.video.api.CfRecommendPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协同过滤在线召回: 取用户近期正反馈内容做种子, 逐个扩散相似度表里的邻居,
 * 候选分 = Σ 种子时间衰减权重 × 相似度。种子越新贡献越大(指数衰减, 半衰期 3 天)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CfRecallService {

    private static final List<EventEnum> POSITIVE_EVENTS = List.of(EventEnum.PLAY, EventEnum.FINISH, EventEnum.LIKE);

    /** 种子窗口与数量上限 */
    private static final int SEED_WINDOW_DAYS = 30;
    private static final int MAX_SEEDS = 20;

    /** 每个种子扩散的邻居数 */
    private static final int NEIGHBORS_PER_SEED = 50;

    /** 种子时间衰减半衰期(天) */
    private static final double SEED_HALF_LIFE_DAYS = 3.0;

    private final IUserVideoBehaviorService behaviorService;
    private final IItemSimilarityService itemSimilarityService;
    private final StringRedisTemplate redisTemplate;

    /** 在线相似度表选择: itemcf | swing, 两份都离线算好, 改配置重启即切换 */
    @Value("${my-youtube.recommend.cf.algorithm:swing}")
    private String algorithmCode;

    public CfRecommendPort.CfCandidates candidates(Long userId, int limit) {
        SimAlgorithmEnum algorithm = SimAlgorithmEnum.fromCode(algorithmCode);
        if (algorithm == null) {
            algorithm = SimAlgorithmEnum.SWING;
        }
        List<Seed> seeds = recentSeeds(userId);
        if (seeds.isEmpty()) {
            // 新用户/无正反馈: CF 路为空, 上游自动退回 tag+热度两路
            return new CfRecommendPort.CfCandidates(algorithm.getCode(), List.of());
        }

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Double> scores = new HashMap<>();
        // 先收齐全部种子再扩散, 否则排序靠后的种子会被当作新候选漏进来
        Set<Long> seedIds = seeds.stream().map(Seed::contentId).collect(Collectors.toSet());
        for (Seed seed : seeds) {
            double ageDays = Math.max(0, ChronoUnit.MINUTES.between(seed.createdAt, now) / 1440.0);
            double seedWeight = Math.pow(0.5, ageDays / SEED_HALF_LIFE_DAYS);
            for (ItemSimilarity neighbor : neighbors(algorithm, seed.contentId)) {
                if (seedIds.contains(neighbor.getItemB())) {
                    continue;
                }
                scores.merge(neighbor.getItemB(), seedWeight * neighbor.getScore(), Double::sum);
            }
        }

        List<Long> candidates = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(Math.max(1, limit))
                .map(Map.Entry::getKey)
                .toList();
        return new CfRecommendPort.CfCandidates(algorithm.getCode(), candidates);
    }

    /**
     * 窗口内最近一次正反馈按内容去重, 时间越新越靠前
     */
    private List<Seed> recentSeeds(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusDays(SEED_WINDOW_DAYS);
        // createdAt 已倒序, LinkedHashMap 按首次出现去重即保每内容最新一条
        Map<Long, UserVideoBehavior> latest = new LinkedHashMap<>();
        behaviorService.lambdaQuery()
                .eq(UserVideoBehavior::getUserId, userId)
                .in(UserVideoBehavior::getEventType, POSITIVE_EVENTS)
                .ge(UserVideoBehavior::getCreatedAt, since)
                .orderByDesc(UserVideoBehavior::getCreatedAt)
                .last("LIMIT 500")
                .list()
                .forEach(b -> latest.putIfAbsent(b.getContentId(), b));
        return latest.values().stream()
                .limit(MAX_SEEDS)
                .map(b -> new Seed(b.getContentId(), b.getCreatedAt()))
                .toList();
    }

    private List<ItemSimilarity> neighbors(SimAlgorithmEnum algorithm, Long contentId) {
        String key = RedisConstants.RECOMMEND_SIM + algorithm.getCode() + ":" + contentId;
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, NEIGHBORS_PER_SEED - 1L);
        if (tuples != null && !tuples.isEmpty()) {
            List<ItemSimilarity> result = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                result.add(new ItemSimilarity()
                        .setAlgorithm(algorithm)
                        .setItemA(contentId)
                        .setItemB(Long.valueOf(t.getValue()))
                        .setScore(t.getScore() != null ? t.getScore() : 0.0));
            }
            return result;
        }
        // 缓存未命中: 回源 MySQL 并回填
        List<ItemSimilarity> rows = itemSimilarityService.neighbors(algorithm, contentId, NEIGHBORS_PER_SEED);
        if (!rows.isEmpty()) {
            backfill(algorithm, contentId, rows);
        }
        return rows;
    }

    private void backfill(SimAlgorithmEnum algorithm, Long contentId, List<ItemSimilarity> rows) {
        try {
            String key = RedisConstants.RECOMMEND_SIM + algorithm.getCode() + ":" + contentId;
            rows.forEach(r -> redisTemplate.opsForZSet().add(key, String.valueOf(r.getItemB()), r.getScore()));
            redisTemplate.expire(key, Duration.ofDays(RedisConstants.RECOMMEND_SIM_EXPIRE));
        } catch (Exception e) {
            log.warn("CF 相似度缓存回填失败 contentId={}", contentId, e);
        }
    }

    private record Seed(Long contentId, LocalDateTime createdAt) {
    }
}
