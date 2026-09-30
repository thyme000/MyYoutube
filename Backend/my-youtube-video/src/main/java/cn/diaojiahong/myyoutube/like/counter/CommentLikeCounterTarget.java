package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.like.domain.constants.VideoLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.VideoCommentLike;
import cn.diaojiahong.myyoutube.like.mapper.VideoCommentLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * comment.like_count 的落库目标。权威值来自 video_comment_like ——
 * 这张表存在之前，评论点赞没有持久化关系，既无法判重也没有 COUNT(*) 真值。
 */
@Component
@RequiredArgsConstructor
public class CommentLikeCounterTarget implements CounterTarget {

    private final IVideoCommentService videoCommentService;
    private final VideoCommentLikeMapper videoCommentLikeMapper;

    @Override
    public CounterType type() {
        return CounterType.COMMENT_LIKE;
    }

    @Override
    public void applyDelta(long commentId, long delta) {
        LambdaUpdateChainWrapper<VideoComment> wrapper = videoCommentService.lambdaUpdate()
                .eq(VideoComment::getId, commentId)
                .setIncrBy(VideoComment::getLikeCount, delta);
        if (delta < 0) {
            wrapper.ge(VideoComment::getLikeCount, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long commentId) {
        return videoCommentLikeMapper.selectCount(new LambdaQueryWrapper<VideoCommentLike>()
                .eq(VideoCommentLike::getCommentId, commentId)
                .eq(VideoCommentLike::getIsLiked, VideoLikeConstants.LIKE));
    }

    @Override
    public void overwrite(long commentId, long value) {
        videoCommentService.lambdaUpdate()
                .eq(VideoComment::getId, commentId)
                .set(VideoComment::getLikeCount, value)
                .update();
    }
}
