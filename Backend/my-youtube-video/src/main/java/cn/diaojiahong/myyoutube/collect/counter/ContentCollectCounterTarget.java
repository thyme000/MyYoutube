package cn.diaojiahong.myyoutube.collect.counter;

import cn.diaojiahong.myyoutube.collect.domain.po.ContentCollect;
import cn.diaojiahong.myyoutube.collect.mapper.ContentCollectMapper;
import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * content.collect_count 的落库目标。权威值是 content_collect 行数。
 */
@Component
@RequiredArgsConstructor
public class ContentCollectCounterTarget implements CounterTarget {

    private final IContentService contentService;
    private final ContentCollectMapper contentCollectMapper;

    @Override
    public CounterType type() {
        return CounterType.CONTENT_COLLECT;
    }

    @Override
    public void applyDelta(long contentId, long delta) {
        LambdaUpdateChainWrapper<Content> wrapper = contentService.lambdaUpdate()
                .eq(Content::getId, contentId)
                .setIncrBy(Content::getCollectCount, delta);
        if (delta < 0) {
            // 减负护底: 只有当前值够减才落
            wrapper.ge(Content::getCollectCount, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long contentId) {
        return contentCollectMapper.selectCount(new LambdaQueryWrapper<ContentCollect>()
                .eq(ContentCollect::getContentId, contentId));
    }

    @Override
    public void overwrite(long contentId, long value) {
        contentService.lambdaUpdate()
                .eq(Content::getId, contentId)
                .set(Content::getCollectCount, value)
                .update();
    }
}
