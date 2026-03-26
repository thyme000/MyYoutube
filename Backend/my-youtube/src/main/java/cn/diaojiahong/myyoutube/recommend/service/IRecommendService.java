package cn.diaojiahong.myyoutube.recommend.service;

import java.util.List;

public interface IRecommendService {
    List<Long> recommend(Long userId, String category, String videoType, int page, int size);
}
