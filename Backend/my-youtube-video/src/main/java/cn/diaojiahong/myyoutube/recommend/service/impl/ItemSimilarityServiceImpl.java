package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.recommend.cf.SimilarityCalculator;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import cn.diaojiahong.myyoutube.recommend.mapper.ItemSimilarityMapper;
import cn.diaojiahong.myyoutube.recommend.service.IItemSimilarityService;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemSimilarityServiceImpl extends ServiceImpl<ItemSimilarityMapper, ItemSimilarity>
        implements IItemSimilarityService {

    private static final List<EventEnum> POSITIVE_EVENTS = List.of(EventEnum.PLAY, EventEnum.FINISH);
    private static final double LIKE_FEEDBACK_WEIGHT = 4.0;

    /** 每个物品保留的邻居数, 控表与控内存 */
    private static final int TOP_K = 100;

    /** 批量写库分片大小 */
    private static final int BATCH_SIZE = 1000;

    private final IUserVideoBehaviorService behaviorService;
    private final ContentLikeMapper likes;
    private final IContentService contentService;
    private final List<SimilarityCalculator> calculators;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactions;

    @Override
    public void rebuildAll() {
        Map<Long, Map<Long, Double>> matrix = loadPositiveMatrix();
        Map<Long, ContentTypeEnum> typeMap = loadContentTypes();
        long contentCount = matrix.values().stream().flatMap(m -> m.keySet().stream()).distinct().count();
        for (SimilarityCalculator calculator : calculators) {
            long start = System.nanoTime();
            List<ItemSimilarity> rows = computePooledRows(calculator, matrix, typeMap);
            long computeMs = (System.nanoTime() - start) / 1_000_000;
            transactions.executeWithoutResult(status -> {
                baseMapper.deleteByAlgorithm(calculator.algorithm());
                insertBatch(rows);
            });
            evictAlgorithm(calculator.algorithm());
            refreshRedis(calculator.algorithm(), groupByItem(rows));
            log.info("CF 全量重建完成 algo={} 用户数={} 内容数={} 分池后相似度行数={} 计算耗时={}ms",
                    calculator.algorithm(), matrix.size(), contentCount, rows.size(), computeMs);
        }
    }

    /**
     * 计算并按内容类型分池: 丢弃跨类型对与类型未知(已删内容)的对, 再按物品截断 Top-K。
     * 分池后每个物品的邻居全部同类型, Top-K 名额不会被其他类型挤占。
     */
    private List<ItemSimilarity> computePooledRows(SimilarityCalculator calculator,
                                                   Map<Long, Map<Long, Double>> matrix,
                                                   Map<Long, ContentTypeEnum> typeMap) {
        return calculator.compute(matrix).stream()
                .map(r -> attachType(r, typeMap))
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toList(), this::topKPerItem));
    }

    private ItemSimilarity attachType(ItemSimilarity row, Map<Long, ContentTypeEnum> typeMap) {
        ContentTypeEnum type = typeMap.get(row.getItemA());
        if (type == null || type != typeMap.get(row.getItemB())) {
            return null;
        }

        return row.setContentType(type);
    }

    /**
     * 内容 id -> 类型(全量, 练手规模百级)
     */
    private Map<Long, ContentTypeEnum> loadContentTypes() {
        return contentService.lambdaQuery()
                .select(Content::getId, Content::getType)
                .list().stream()
                .filter(c -> c.getType() != null)
                .collect(Collectors.toMap(Content::getId, Content::getType));
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
     * 正反馈矩阵：播放、完播及当前有效点赞。
     */
    private Map<Long, Map<Long, Double>> loadPositiveMatrix() {
        Map<Long, Map<Long, Double>> matrix = new HashMap<>();
        behaviorService.lambdaQuery()
                .in(UserVideoBehavior::getEventType, POSITIVE_EVENTS)
                .list()
                .forEach(b -> matrix.computeIfAbsent(b.getUserId(), k -> new HashMap<>())
                        .merge(b.getContentId(), (double) b.getEventType().getScore(), Double::sum));
        likes.selectList(new LambdaQueryWrapper<ContentLike>().select(ContentLike::getUserId, ContentLike::getContentId))
                .forEach(like -> matrix.computeIfAbsent(like.getUserId(), k -> new HashMap<>())
                        .merge(like.getContentId(), LIKE_FEEDBACK_WEIGHT, Double::sum));
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
            public Object execute(RedisOperations operations) {
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

    private void evictAlgorithm(SimAlgorithmEnum algorithm) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisConstants.RECOMMEND_SIM + algorithm.getCode() + ":*").build();
        List<String> staleKeys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(staleKeys::add);
        }
        if (!staleKeys.isEmpty()) {
            redisTemplate.delete(staleKeys);
        }
    }
}
