package cn.diaojiahong.myyoutube.share.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.share.domain.po.ContentShare;
import cn.diaojiahong.myyoutube.share.mapper.ContentShareMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * content.share_count 的重算钩子: 写路径已直更不走缓冲落库, 管理端重算工具靠它对账覆盖。
 * 权威值是 content_share 行数。
 */
@Component
public class ContentShareCounterTarget extends AbstractCounterTarget<Content> {

    private final ContentShareMapper shares;

    public ContentShareCounterTarget(IContentService contentService, ContentShareMapper shares) {
        super(contentService, Content::getId, Content::getShareCount);
        this.shares = shares;
    }

    @Override
    public CounterType type() {
        return CounterType.CONTENT_SHARE;
    }

    @Override
    public Long authoritativeValue(long contentId) {
        return shares.selectCount(new LambdaQueryWrapper<ContentShare>()
                .eq(ContentShare::getContentId, contentId));
    }
}
