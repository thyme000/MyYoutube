package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * content.like_count 的落库目标。权威值是 content_like 的状态机，不是计数器自己的值。
 */
@Component
@RequiredArgsConstructor
public class ContentLikeCounterTarget implements CounterTarget {

    private final IContentService contentService;
    private final ContentLikeMapper contentLikeMapper;

    @Override
    public CounterType type() {
        return CounterType.CONTENT_LIKE;
    }

    @Override
    public void applyDelta(long contentId, long delta) {
        LambdaUpdateChainWrapper<Content> wrapper = contentService.lambdaUpdate()
                .eq(Content::getId, contentId)
                .setIncrBy(Content::getLikeCount, delta);
        if (delta < 0) {
            // 减负护底: 只有当前值够减才落。替代原先挂在写路径上的 >0 守卫
            wrapper.ge(Content::getLikeCount, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long contentId) {
        return contentLikeMapper.selectCount(new LambdaQueryWrapper<ContentLike>()
                .eq(ContentLike::getContentId, contentId)
                .eq(ContentLike::getIsLiked, ContentLikeConstants.LIKE));
    }

    @Override
    public void overwrite(long contentId, long value) {
        contentService.lambdaUpdate()
                .eq(Content::getId, contentId)
                .set(Content::getLikeCount, value)
                .update();
    }
}
