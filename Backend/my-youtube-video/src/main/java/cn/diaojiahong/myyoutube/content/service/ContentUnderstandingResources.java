package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 仅供完成访问校验的展示读入口使用，不缓存签名 URL 或审核过程状态。
 * 未接收完成的记录按不存在处理，仅使用短负缓存；完成产物按产物 TTL 缓存。
 * 接收事务提交后立即删除并延迟再删一次，尽力清理先前的负缓存。
 * 仍可能在第二次删除之后迟到回填负缓存；该条目从回填时起按负缓存 TTL 过期。
 * 当前完成产物不再改写；以后新增重算或替换能力时，必须同时扩展失效或版本策略。
 */
@Service
@RequiredArgsConstructor
public class ContentUnderstandingResources {
    private final ContentResourceCache cache;
    private final ContentUnderstandingResourceLoader loader;

    public ContentUnderstandingResource get(Long id) {
        return cache.get(ContentResourceRegion.UNDERSTANDING, id.toString(),
                ContentUnderstandingResource.class, () -> loader.load(id));
    }
}
