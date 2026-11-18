package cn.diaojiahong.myyoutube.recommend.cf;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper.Seed;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.recommend.service.impl.ItemSimilarityServiceImpl;
import cn.diaojiahong.myyoutube.video.api.CfRecommendPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协同过滤在线召回: 取用户近期正反馈内容做种子, 逐个扩散相似度表里的邻居,
 * 候选分 = Σ 种子时间衰减权重 × 相似度。种子越新贡献越大(指数衰减, 半衰期 3 天)。
 */
@Service
@RequiredArgsConstructor
public class CfRecallService {

    /** 种子窗口与数量上限 */
    private static final int SEED_WINDOW_DAYS = 30;
    private static final int MAX_SEEDS = 20;

    /** 每个种子扩散的邻居数 */
    private static final int NEIGHBORS_PER_SEED = 50;

    /** 种子时间衰减半衰期(天) */
    private static final double SEED_HALF_LIFE_DAYS = 3.0;

    private final UserVideoBehaviorMapper behaviorMapper;
    private final IItemSimilarityService itemSimilarityService;
    private final IContentService contentService;
    private final StringRedisTemplate redisTemplate;

    /** 在线相似度表选择: itemcf | swing, 两份都离线算好, 改配置重启即切换 */
    @Value("${my-youtube.recommend.cf.algorithm:swing}")
    private String algorithmCode;

    public CfRecommendPort.CfCandidates candidates(Long userId, String type, int limit) {
        SimAlgorithmEnum algorithm = SimAlgorithmEnum.fromCode(algorithmCode);
        if (algorithm == null) {
            algorithm = SimAlgorithmEnum.SWING;
        }
        List<Seed> seeds = recentSeeds(userId);
        if (!seeds.isEmpty() && type != null && !type.isBlank()) {
            ContentTypeEnum contentType = ContentTypeEnum.fromCode(type);
            if (contentType == null) {
                // 未知类型按空池处理: 独立流宁缺勿错
                return new CfRecommendPort.CfCandidates(algorithm.getCode(), List.of());
            }
            // 独立类型流: 先按类型过滤种子, 剩下的种子照常走时间衰减加权扩散
            seeds = filterSeedsByType(seeds, contentType);
        }
        if (seeds.isEmpty()) {
            // 新用户/无正反馈/该类型无种子: CF 路为空, 上游自动退回 tag+热度两路
            return new CfRecommendPort.CfCandidates(algorithm.getCode(), List.of());
        }

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Double> scores = new HashMap<>();
        // 先收齐全部种子再扩散, 否则排序靠后的种子会被当作新候选漏进来
        Set<Long> seedIds = seeds.stream().map(Seed::contentId).collect(Collectors.toSet());
        for (Seed seed : seeds) {
            double ageDays = Math.max(0, ChronoUnit.MINUTES.between(seed.createdAt(), now) / 1440.0);
            double seedWeight = Math.pow(0.5, ageDays / SEED_HALF_LIFE_DAYS);
            for (ItemSimilarity neighbor : neighbors(algorithm, seed.contentId())) {
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
        return behaviorMapper.recentPositiveSeeds(userId, since, MAX_SEEDS);
    }

    /**
     * 主键 IN 点查种子的类型(不加载全表映射), 只保留目标类型的种子
     */
    private List<Seed> filterSeedsByType(List<Seed> seeds, ContentTypeEnum contentType) {
        List<Long> seedIds = seeds.stream().map(Seed::contentId).toList();
        Set<Long> typedIds = contentService.lambdaQuery()
                .select(Content::getId)
                .in(Content::getId, seedIds)
                .eq(Content::getType, contentType)
                .list().stream().map(Content::getId).collect(Collectors.toSet());
        return seeds.stream().filter(s -> typedIds.contains(s.contentId())).toList();
    }

    private List<ItemSimilarity> neighbors(SimAlgorithmEnum algorithm, Long contentId) {
        String key = ItemSimilarityServiceImpl.similarityKey(algorithm.getCode(), contentId);
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
        // 缓存统一由每日全量重建写入, 回源只查 MySQL 不回填,
        // 避免在线请求拿着重建前读到的旧邻居在重建完成后写回、污染新缓存
        return itemSimilarityService.neighbors(algorithm, contentId, NEIGHBORS_PER_SEED);
    }
}
