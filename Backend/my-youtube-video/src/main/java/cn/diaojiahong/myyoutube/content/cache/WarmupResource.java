package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;

import java.util.function.Supplier;

/** 预热前登记资源类型与回源操作；loader 在独立只读事务中运行，不操作批次状态。 */
public record WarmupResource<T>(ContentResourceRegion region, Supplier<T> loader, FillPolicy policy) {}
