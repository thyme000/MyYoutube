package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.video.port.RecommendPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RecommendPortImpl implements RecommendPort {

    private final IRecommendService recommendService;

    @Override
    public List<Long> recommend(Long userId, String category, String videoType, int page, int size) {
        return recommendService.recommend(userId, category, videoType, page, size);
    }

    @Override
    public List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size) {
        return recommendService.recommendForFollow(followIds, userId, page, size);
    }
}
