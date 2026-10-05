package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.recommend.cf.SimilarityCalculator;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import cn.diaojiahong.myyoutube.recommend.mapper.ItemSimilarityMapper;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemSimilarityServiceImpl extends ServiceImpl<ItemSimilarityMapper, ItemSimilarity>
        implements IItemSimilarityService {

    private static final List<EventEnum> POSITIVE_EVENTS = List.of(EventEnum.PLAY, EventEnum.FINISH, EventEnum.LIKE);

    /** 每个物品保留的邻居数, 控表与控内存 */
    private static final int TOP_K = 100;

    /** 批量写库分片大小 */
    private static final int BATCH_SIZE = 1000;

    private static final String WATERMARK_KEY = RedisConstants.SCHEDULE_CF_WATERMARK;

    private final IUserVideoBehaviorService behaviorService;
    private final List<SimilarityCalculator> calculators;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void rebuildAll() {
        Map<Long, Map<Long, Double>> matrix = loadPositiveMatrix();
        long contentCount = matrix.values().stream().flatMap(m -> m.keySet().stream()).distinct().count();
        for (SimilarityCalculator calculator : calculators) {
            List<ItemSimilarity> rows = topKPerItem(calculator.compute(matrix));
            removeByAlgorithm(calculator.algorithm());
            insertBatch(rows);
            refreshRedis(calculator.algorithm(), groupByItem(rows));
            log.info("CF 全量重建完成 algo={} 用户数={} 内容数={} 相似度行数={}",
                    calculator.algorithm(), matrix.size(), contentCount, rows.size());
        }
        saveWatermark(behaviorService.lambdaQuery()
                .in(UserVideoBehavior::getEventType, POSITIVE_EVENTS)
                .orderByDesc(UserVideoBehavior::getId)
                .last("LIMIT 1")
                .oneOpt().map(UserVideoBehavior::getId).orElse(0L));
    }

    @Override
    public void refreshIncremental() {
        long watermark = readWatermark();
        List<UserVideoBehavior> fresh = behaviorService.lambdaQuery()
                .gt(UserVideoBehavior::getId, watermark)
                .in(UserVideoBehavior::getEventType, POSITIVE_EVENTS)
                .list();
        if (fresh.isEmpty()) {
            return;
        }
        // 只重算新行为涉及的内容; 矩阵仍全量加载(练手规模秒级), 增量省的是重算与写库
        Set<Long> affected = fresh.stream().map(UserVideoBehavior::getContentId).collect(Collectors.toSet());
        Map<Long, Map<Long, Double>> matrix = loadPositiveMatrix();
        for (SimilarityCalculator calculator : calculators) {
            List<ItemSimilarity> rows = calculator.compute(matrix).stream()
                    .filter(r -> affected.contains(r.getItemA()))
                    .collect(Collectors.toList());
            rows = topKPerItem(rows);
            upsertBatch(rows);
            refreshRedis(calculator.algorithm(), groupByItem(rows));
            log.info("CF 增量刷新完成 algo={} 涉及内容数={} 行数={}", calculator.algorithm(), affected.size(), rows.size());
        }
        saveWatermark(fresh.stream().mapToLong(UserVideoBehavior::getId).max().orElse(watermark));
    }

    @Override
    public List<ItemSimilarity> neighbors(SimAlgorithmEnum algorithm, Long contentId, int limit) {
        return lambdaQuery()
                .eq(ItemSimilarity::getAlgorithm, algorithm)
                .eq(ItemSimilarity::getItemA, contentId)
                .orderByDesc(ItemSimilarity::getScore)
                .last("LIMIT " + Math.max(1, limit))
                .list();
    }

    /**
     * 正反馈矩阵: 用户 -> (内容 -> 该内容上的行为分合计), 只含 play/finish/like
     */
    private Map<Long, Map<Long, Double>> loadPositiveMatrix() {
        Map<Long, Map<Long, Double>> matrix = new HashMap<>();
        behaviorService.lambdaQuery()
                .in(UserVideoBehavior::getEventType, POSITIVE_EVENTS)
                .list()
                .forEach(b -> matrix.computeIfAbsent(b.getUserId(), k -> new HashMap<>())
                        .merge(b.getContentId(), (double) b.getEventType().getScore(), Double::sum));
        return matrix;
    }

    /**
     * 按物品截断 Top-K 邻居
     */
    private List<ItemSimilarity> topKPerItem(List<ItemSimilarity> rows) {
        Map<Long, List<ItemSimilarity>> byItem = new HashMap<>();
        rows.forEach(r -> byItem.computeIfAbsent(r.getItemA(), k -> new ArrayList<>()).add(r));
        List<ItemSimilarity> truncated = new ArrayList<>();
        byItem.values().forEach(list -> {
            list.sort(Comparator.comparingDouble(ItemSimilarity::getScore).reversed());
            truncated.addAll(list.subList(0, Math.min(TOP_K, list.size())));
        });
        return truncated;
    }

    private Map<Long, List<ItemSimilarity>> groupByItem(List<ItemSimilarity> rows) {
        return rows.stream().collect(Collectors.groupingBy(ItemSimilarity::getItemA));
    }

    private void refreshRedis(SimAlgorithmEnum algorithm, Map<Long, List<ItemSimilarity>> byItem) {
        if (byItem.isEmpty()) {
            return;
        }
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                byItem.forEach((contentId, neighbors) -> {
                    String key = RedisConstants.RECOMMEND_SIM + algorithm.getCode() + ":" + contentId;
                    operations.delete(key);
                    neighbors.forEach(n -> operations.opsForZSet()
                            .add(key, String.valueOf(n.getItemB()), n.getScore()));
                    operations.expire(key, Duration.ofDays(RedisConstants.RECOMMEND_SIM_EXPIRE));
                });
                return null;
            }
        });
    }

    private void insertBatch(List<ItemSimilarity> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            baseMapper.batchInsert(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }
    }

    private void upsertBatch(List<ItemSimilarity> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            baseMapper.batchUpsert(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }
    }

    private void removeByAlgorithm(SimAlgorithmEnum algorithm) {
        baseMapper.deleteByAlgorithm(algorithm);
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisConstants.RECOMMEND_SIM + algorithm.getCode() + ":*").build();
        List<String> staleKeys = new ArrayList<>();
        try (var cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(staleKeys::add);
        }
        if (!staleKeys.isEmpty()) {
            redisTemplate.delete(staleKeys);
        }
    }

    private long readWatermark() {
        String v = redisTemplate.opsForValue().get(WATERMARK_KEY);
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void saveWatermark(long id) {
        redisTemplate.opsForValue().set(WATERMARK_KEY, String.valueOf(id));
    }
}
