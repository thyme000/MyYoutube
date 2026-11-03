package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContentMediaResourceLoader {
    private final ContentTypeStrategyRegistry strategies;

    // 仅由事务外的资源读入口调用；独立只读事务限制查询时间，不负责写入后的缓存失效。
    @Transactional(readOnly = true, timeout = 5, propagation = Propagation.REQUIRES_NEW)
    public ContentMediaResource load(Content content) {
        return strategies.get(content.getType()).loadResource(content.getId());
    }
}
