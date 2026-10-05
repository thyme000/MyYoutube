package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 物品相似度(ItemCF/Swing 离线产物)
 */
public interface IItemSimilarityService extends IService<ItemSimilarity> {

    /**
     * 全量重建: 重算两个算法的相似度并覆盖 MySQL 与 Redis
     */
    void rebuildAll();

    /**
     * 增量刷新: 只重算水位之后新行为涉及的内容, upsert 差异行并刷新对应 Redis key
     */
    void refreshIncremental();

    /**
     * 兜底查邻居(Redis 未命中时走 MySQL)
     */
    List<ItemSimilarity> neighbors(SimAlgorithmEnum algorithm, Long contentId, int limit);
}
