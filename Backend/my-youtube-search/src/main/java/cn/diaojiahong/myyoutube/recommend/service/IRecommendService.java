package cn.diaojiahong.myyoutube.recommend.service;

import java.util.List;

public interface IRecommendService {
    /**
     * @param type 内容类型: null/空=混合流, 非空=独立类型流(三路召回均限定该类型)
     */
    List<Long> recommend(Long userId, String type, String category, int page, int size);

    List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size);
}
