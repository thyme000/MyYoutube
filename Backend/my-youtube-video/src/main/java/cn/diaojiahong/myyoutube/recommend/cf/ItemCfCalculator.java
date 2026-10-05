package cn.diaojiahong.myyoutube.recommend.cf;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经典 ItemCF: sim(i,j) = Σ_u w(u,i)*w(u,j) / sqrt(|U(i)|*|U(j)|)
 * 分母用两个内容各自的正反馈用户数, 抵消热门内容的天然高共现。
 */
@Component
public class ItemCfCalculator implements SimilarityCalculator {

    @Override
    public SimAlgorithmEnum algorithm() {
        return SimAlgorithmEnum.ITEMCF;
    }

    @Override
    public List<ItemSimilarity> compute(Map<Long, Map<Long, Double>> userItemWeights) {
        // 共现加权和: i -> (j -> Σ w(u,i)*w(u,j)), 对称矩阵只算上三角
        Map<Long, Map<Long, Double>> coOccurrence = new HashMap<>();
        // |U(i)|: 各内容的正反馈去重用户数
        Map<Long, Integer> itemCount = new HashMap<>();

        for (Map<Long, Double> items : userItemWeights.values()) {
            for (Map.Entry<Long, Double> e1 : items.entrySet()) {
                itemCount.merge(e1.getKey(), 1, Integer::sum);
                for (Map.Entry<Long, Double> e2 : items.entrySet()) {
                    if (e1.getKey().equals(e2.getKey())) {
                        continue;
                    }
                    coOccurrence.computeIfAbsent(e1.getKey(), k -> new HashMap<>())
                            .merge(e2.getKey(), e1.getValue() * e2.getValue(), Double::sum);
                }
            }
        }

        return toRows(coOccurrence, itemCount);
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
