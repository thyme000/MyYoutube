package cn.diaojiahong.myyoutube.recommend.cf;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经典 ItemCF: sim(i,j) = Σ_u w(u,i)*w(u,j) / sqrt(|U(i)|*|U(j)|)
 * 分母用两个内容各自的正反馈用户数, 抵消热门内容的天然高共现。
 */
@Component
public class ItemCfCalculator implements SimilarityCalculator {

    private final SimComputePool pool;

    public ItemCfCalculator(SimComputePool pool) {
        this.pool = pool;
    }

    @Override
    public SimAlgorithmEnum algorithm() {
        return SimAlgorithmEnum.ITEMCF;
    }

    /**
     * worker 局部累积: 共现加权和 + 各内容正反馈用户数
     */
    private static final class LocalAcc {
        final Map<Long, Map<Long, Double>> coOccurrence = new HashMap<>();
        final Map<Long, Integer> itemCount = new HashMap<>();
    }

    @Override
    public List<ItemSimilarity> compute(Map<Long, Map<Long, Double>> userItemWeights) {
        // 任务 = 单个用户的行为展开, 不同用户会写重叠的 (i,j) 对, 靠末尾求和归约;
        // 按用户物品数降序, 重用户先被领走。
        List<Map.Entry<Long, Map<Long, Double>>> tasks = new ArrayList<>(userItemWeights.entrySet());
        tasks.sort(Comparator.comparingInt((Map.Entry<Long, Map<Long, Double>> e) -> e.getValue().size()).reversed());

        LocalAcc merged = pool.collectAndMerge(
                tasks,
                LocalAcc::new,
                (local, task) -> accumulate(task.getValue(), local),
                (a, b) -> {
                    b.itemCount.forEach((k, v) -> a.itemCount.merge(k, v, Integer::sum));
                    b.coOccurrence.forEach((i, inner) -> {
                        Map<Long, Double> target = a.coOccurrence.computeIfAbsent(i, k -> new HashMap<>());
                        inner.forEach((j, w) -> target.merge(j, w, Double::sum));
                    });
                    return a;
                });

        return toRows(merged.coOccurrence, merged.itemCount);
    }

    private void accumulate(Map<Long, Double> items, LocalAcc local) {
        for (Map.Entry<Long, Double> e1 : items.entrySet()) {
            local.itemCount.merge(e1.getKey(), 1, Integer::sum);
            for (Map.Entry<Long, Double> e2 : items.entrySet()) {
                if (e1.getKey().equals(e2.getKey())) {
                    continue;
                }
                local.coOccurrence.computeIfAbsent(e1.getKey(), k -> new HashMap<>())
                        .merge(e2.getKey(), e1.getValue() * e2.getValue(), Double::sum);
            }
        }
    }

    private List<ItemSimilarity> toRows(Map<Long, Map<Long, Double>> coOccurrence,
                                        Map<Long, Integer> itemCount) {
        List<ItemSimilarity> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, Double>> entry : coOccurrence.entrySet()) {
            long i = entry.getKey();
            for (Map.Entry<Long, Double> neighbor : entry.getValue().entrySet()) {
                long j = neighbor.getKey();
                if (i > j) {
                    // 对称矩阵, 上三角遍历时输出双向, 避免重复
                    continue;
                }
                double denom = Math.sqrt((double) itemCount.getOrDefault(i, 0) * itemCount.getOrDefault(j, 0));
                if (denom <= 0) {
                    continue;
                }
                double score = neighbor.getValue() / denom;
                rows.add(newRow(i, j, score));
                rows.add(newRow(j, i, score));
            }
        }
        return rows;
    }

    private ItemSimilarity newRow(long itemA, long itemB, double score) {
        return new ItemSimilarity()
                .setAlgorithm(algorithm())
                .setItemA(itemA)
                .setItemB(itemB)
                .setScore(score);
    }
}
