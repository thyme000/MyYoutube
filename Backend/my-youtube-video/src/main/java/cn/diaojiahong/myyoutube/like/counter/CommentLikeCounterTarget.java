package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentCommentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentCommentLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * comment.like_count 的落库目标。权威值来自 content_comment_like ——
 * 这张表存在之前，评论点赞没有持久化关系，既无法判重也没有 COUNT(*) 真值。
 */
@Component
@RequiredArgsConstructor
public class CommentLikeCounterTarget implements CounterTarget {

    private final IContentCommentService contentCommentService;
    private final ContentCommentLikeMapper contentCommentLikeMapper;

    @Override
    public CounterType type() {
        return CounterType.COMMENT_LIKE;
    }

    @Override
    public void applyDelta(long commentId, long delta) {
        LambdaUpdateChainWrapper<ContentComment> wrapper = contentCommentService.lambdaUpdate()
                .eq(ContentComment::getId, commentId)
                .setIncrBy(ContentComment::getLikeCount, delta);
        if (delta < 0) {
            wrapper.ge(ContentComment::getLikeCount, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long commentId) {
        return contentCommentLikeMapper.selectCount(new LambdaQueryWrapper<ContentCommentLike>()
                .eq(ContentCommentLike::getCommentId, commentId)
                .eq(ContentCommentLike::getIsLiked, ContentLikeConstants.LIKE));
    }

    @Override
    public void overwrite(long commentId, long value) {
        contentCommentService.lambdaUpdate()
                .eq(ContentComment::getId, commentId)
                .set(ContentComment::getLikeCount, value)
                .update();
    }
}
