package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;

import java.util.function.Supplier;

/**
 * 登记预热资源及加载操作。loader 在独立工作线程执行，不依赖提交线程的事务，也不修改批次状态。
 * 需要查库的 loader 自行开启独立只读事务。
 */
public record WarmupResource<T>(ContentResourceRegion region, String resourceId, Supplier<T> loader, FillPolicy policy) {
    public String key() {
        return region.key(resourceId);
    }
}
