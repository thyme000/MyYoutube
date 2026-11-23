package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * content.like_count 的落库目标，权威值来自 content_like 的有效点赞关系。
 * 同一事务里把差额记入 author_like_count。
 */
@Component
public class ContentLikeCounterTarget extends AbstractCounterTarget<Content> {

    private final ContentLikeMapper contentLikeMapper;
    private final AuthorLikeCountService authorLikeCounts;

    public ContentLikeCounterTarget(IContentService contentService, ContentLikeMapper contentLikeMapper,
                                    AuthorLikeCountService authorLikeCounts) {
        super(contentService, Content::getId, Content::getLikeCount);
        this.contentLikeMapper = contentLikeMapper;
        this.authorLikeCounts = authorLikeCounts;
    }

    @Override
    public CounterType type() {
        return CounterType.CONTENT_LIKE;
    }

    @Override
    public void applyDelta(long contentId, long delta) {
        authorLikeCounts.apply(contentId, delta);
    }

    @Override
    public void overwrite(long contentId, long truth) {
        authorLikeCounts.overwrite(contentId, truth);
    }

    @Override
    public Long authoritativeValue(long contentId) {
        return contentLikeMapper.selectCount(new LambdaQueryWrapper<ContentLike>()
                .eq(ContentLike::getContentId, contentId));
    }
}
