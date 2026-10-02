package cn.diaojiahong.myyoutube.recommend.service;

import java.util.List;

public interface IRecommendService {
    List<Long> recommend(Long userId, String category, int page, int size);

    List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size);
}
