package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RecommendPortImpl implements RecommendPort {

    private final IRecommendService recommendService;

    @Override
    public List<Long> recommend(RecommendPort.RecommendQuery query) {
        return recommendService.recommend(query.getUserId(), query.getCategory(),
                query.getPage(), query.getSize());
    }

    @Override
    public List<Long> recommendForFollow(RecommendPort.FollowRecommendQuery query) {
        return recommendService.recommendForFollow(query.getFollowIds(), query.getUserId(),
                query.getPage(), query.getSize());
    }
}
