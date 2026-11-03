package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.service.ContentMediaResourceLoader;
import cn.diaojiahong.myyoutube.content.service.ContentMediaResources;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;

import java.util.function.Supplier;

import static org.mockito.Mockito.*;

public final class ContentResourceTestSupport {
    private ContentResourceTestSupport() {}

    /** 既有接口契约测试始终回源；缓存命中和资源复用在专门测试中验证。 */
    public static ContentMediaResources uncached(ContentTypeStrategyRegistry strategies,
            S3StorageUtil storage) {
        ContentResourceCache cache = mock(ContentResourceCache.class);
        when(cache.get(any(), anyString(), any(), any())).thenAnswer(call -> {
            Supplier<?> loader = call.getArgument(3);
            return loader.get();
        });
        return new ContentMediaResources(cache, new ContentMediaResourceLoader(strategies), storage);
    }
}
