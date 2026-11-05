package cn.diaojiahong.myyoutube.content.cache;

/** 预热取锁发生依赖故障，应跳过该资源，不再加载产物。 */
public class ContentResourceLockUnavailableException extends RuntimeException {
    public ContentResourceLockUnavailableException(Throwable cause) {
        super("Content resource warmup lock unavailable", cause);
    }
}
