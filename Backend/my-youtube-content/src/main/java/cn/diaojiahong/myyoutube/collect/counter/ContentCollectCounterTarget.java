package cn.diaojiahong.myyoutube.collect.counter;

import cn.diaojiahong.myyoutube.collect.domain.po.ContentCollect;
import cn.diaojiahong.myyoutube.collect.mapper.ContentCollectMapper;
import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * content.collect_count 的落库目标。权威值是 content_collect 行数。
 */
@Component
public class ContentCollectCounterTarget extends AbstractCounterTarget<Content> {

    private final ContentCollectMapper contentCollectMapper;

    public ContentCollectCounterTarget(IContentService contentService, ContentCollectMapper contentCollectMapper) {
        super(contentService, Content::getId, Content::getCollectCount);
        this.contentCollectMapper = contentCollectMapper;
    }

    @Override
    public CounterType type() {
        return CounterType.CONTENT_COLLECT;
    }

    @Override
    public Long authoritativeValue(long contentId) {
        return contentCollectMapper.selectCount(new LambdaQueryWrapper<ContentCollect>()
                .eq(ContentCollect::getContentId, contentId));
    }
}
