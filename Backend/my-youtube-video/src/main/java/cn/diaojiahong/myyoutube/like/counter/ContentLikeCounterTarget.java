package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * content.like_count 的落库目标。权威值是 content_like 的状态机，不是计数器自己的值。
 */
@Component
public class ContentLikeCounterTarget extends AbstractCounterTarget<Content> {

    private final ContentLikeMapper contentLikeMapper;

    public ContentLikeCounterTarget(IContentService contentService, ContentLikeMapper contentLikeMapper) {
        super(contentService, Content::getId, Content::getLikeCount);
        this.contentLikeMapper = contentLikeMapper;
    }

    @Override
    public CounterType type() {
        return CounterType.CONTENT_LIKE;
    }

    @Override
    public Long authoritativeValue(long contentId) {
        return contentLikeMapper.selectCount(new LambdaQueryWrapper<ContentLike>()
                .eq(ContentLike::getContentId, contentId)
                .eq(ContentLike::getIsLiked, ContentLikeConstants.LIKE));
    }
}
