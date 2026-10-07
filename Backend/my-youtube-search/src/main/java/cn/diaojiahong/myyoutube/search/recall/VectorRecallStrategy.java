package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量语义召回路: kNN 只召回与查询向量同版本的已发布内容
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class VectorRecallStrategy implements RecallStrategy {

    private final ContentVectorService contentVectorService;
    private final VectorProperties vectorProperties;

    @Override
    public boolean shouldRun(SearchContext ctx) {
        return ctx.getQueryVector() != null;
    }

    @Override
    public List<String> recall(SearchContext ctx) {
        return contentVectorService.knnSearch(ctx.getQueryVector(), ctx.getCategory(),
                vectorProperties.getRecallSize());
    }
}
