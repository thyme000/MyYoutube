package cn.diaojiahong.myyoutube.recommend.cf;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;

import java.util.List;
import java.util.Map;

/**
 * 物品相似度计算器。输入正反馈矩阵(用户 -> 内容 -> 行为分),
 * 输出全量相似度记录(item_a/item_b 双向都含), Top-K 截断由落库方统一做。
 */
public interface SimilarityCalculator {

    SimAlgorithmEnum algorithm();

    /**
     * @param userItemWeights 用户 -> (内容ID -> 该内容上的正反馈分值合计)
     * @return 相似度记录(item_a/item_b 双向都含)
     */
    List<ItemSimilarity> compute(Map<Long, Map<Long, Double>> userItemWeights);
}
