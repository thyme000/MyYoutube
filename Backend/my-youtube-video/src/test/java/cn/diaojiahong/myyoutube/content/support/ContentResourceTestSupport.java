package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.content.service.ContentAccessSnapshotLoader;
import cn.diaojiahong.myyoutube.content.service.ContentDisplayAccess;
import cn.diaojiahong.myyoutube.content.service.ContentMediaResourceLoader;
import cn.diaojiahong.myyoutube.content.service.ContentMediaResources;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;

import java.util.function.Supplier;

import static org.mockito.Mockito.*;

/**
 * 契约测试始终回源；真实缓存与服务组合由 ContentMediaResourcesTest 覆盖，
 * Spring 事务装配由 ContentStatisticsIntegrationTest 覆盖，不能替代真实 MySQL 隔离测试。
 */
public final class ContentResourceTestSupport {
    private ContentResourceTestSupport() {}

    /** 既有接口契约测试始终回源；缓存命中和资源复用在专门测试中验证。 */
    public static ContentMediaResources uncached(ContentTypeStrategyRegistry strategies,
            S3StorageUtil storage) {
        return new ContentMediaResources(uncachedCache(), new ContentMediaResourceLoader(strategies), storage);
    }

    public static ContentDisplayAccess uncachedAccess(ContentMapper contents, AudioContentAccess audio) {
        return new ContentDisplayAccess(uncachedCache(), new ContentAccessSnapshotLoader(contents, audio),
                new ContentAccessService(contents, audio));
    }

    private static ContentResourceCache uncachedCache() {
        ContentResourceCache cache = mock(ContentResourceCache.class);
        when(cache.get(any(), anyString(), any(), any())).thenAnswer(call -> {
            Supplier<?> loader = call.getArgument(3);
            return loader.get();
        });
        return cache;
    }
}
