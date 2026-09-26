package cn.diaojiahong.myyoutube.video.port;

import java.util.List;

/**
 * 推荐端口: 由 search 模块提供实现
 */
public interface RecommendPort {

    List<Long> recommend(Long userId, String category, String videoType, int page, int size);

    List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size);
}
