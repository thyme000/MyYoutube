package cn.diaojiahong.myyoutube.content.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * content.share_count 的落库目标。分享只增不减、无权威来源，不做对账覆盖。
 */
@Component
@RequiredArgsConstructor
public class ContentShareCounterTarget implements CounterTarget {

    private final IContentService contentService;

    @Override
    public CounterType type() {
        return CounterType.CONTENT_SHARE;
    }

    @Override
    public void applyDelta(long contentId, long delta) {
        contentService.lambdaUpdate()
                .eq(Content::getId, contentId)
                .setIncrBy(Content::getShareCount, delta)
                .update();
    }

    @Override
    public Long authoritativeValue(long contentId) {
        // 无权威来源, 对账跳过
        return null;
    }

    @Override
    public void overwrite(long contentId, long value) {
        // 对账不覆盖, 保留空实现
    }
}
