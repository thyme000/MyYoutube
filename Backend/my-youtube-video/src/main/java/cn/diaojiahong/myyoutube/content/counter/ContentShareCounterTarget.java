package cn.diaojiahong.myyoutube.content.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import org.springframework.stereotype.Component;

/**
 * content.share_count 的落库目标。只增不减、没有可信权威来源(转发无法撤销), 对账跳过。
 */
@Component
public class ContentShareCounterTarget extends AbstractCounterTarget<Content> {

    public ContentShareCounterTarget(IContentService contentService) {
        super(contentService, Content::getId, Content::getShareCount);
    }

    @Override
    public CounterType type() {
        return CounterType.CONTENT_SHARE;
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
