package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentCommentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentCommentLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * comment.like_count 的落库目标。权威值是 content_comment_like 的状态机。
 */
@Component
public class CommentLikeCounterTarget extends AbstractCounterTarget<ContentComment> {

    private final ContentCommentLikeMapper contentCommentLikeMapper;

    public CommentLikeCounterTarget(IContentCommentService contentCommentService,
                                    ContentCommentLikeMapper contentCommentLikeMapper) {
        super(contentCommentService, ContentComment::getId, ContentComment::getLikeCount);
        this.contentCommentLikeMapper = contentCommentLikeMapper;
    }

    @Override
    public CounterType type() {
        return CounterType.COMMENT_LIKE;
    }

    @Override
    public Long authoritativeValue(long commentId) {
        return contentCommentLikeMapper.selectCount(new LambdaQueryWrapper<ContentCommentLike>()
                .eq(ContentCommentLike::getCommentId, commentId)
                .eq(ContentCommentLike::getIsLiked, ContentLikeConstants.LIKE));
    }
}
