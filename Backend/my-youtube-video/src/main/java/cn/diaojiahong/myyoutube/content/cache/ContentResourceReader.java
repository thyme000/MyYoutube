package cn.diaojiahong.myyoutube.content.cache;

import java.util.function.Supplier;

/**
 * 单项读取与请求内批量读取共用的加载契约，读取结果不代表访问授权。
 * 结果允许来自预取快照，不保证每次调用时均为最新值。
 * 持有预取快照的批量 reader 仅供本次请求使用，不能跨请求保存。
 */
public interface ContentResourceReader {
    /**
     * type 使用具体 DTO、数组或字符串；顶层泛型集合应包装在具体 DTO 中。
     * loader 正常返回 null 表示不存在，抛出异常不能作为不存在的结果缓存。
     * 所有读取均须显式传入策略；状态和负缓存仍使用各自的 TTL。
     */
    <T> T get(ContentResourceRegion region, String resourceId, Class<T> type, Supplier<T> loader, FillPolicy policy);

    enum FillPolicy {
        /** 达到累计播放量门槛的产物及大 V 发布预热使用常规 TTL。 */
        STANDARD,
        /** 未达门槛的产物使用短 TTL，同样加锁、双检和回填。 */
        SHORT_TTL
    }
}
