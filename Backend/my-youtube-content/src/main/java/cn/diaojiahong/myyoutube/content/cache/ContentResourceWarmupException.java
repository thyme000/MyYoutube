package cn.diaojiahong.myyoutube.content.cache;

import lombok.Getter;

/** 成功项写入且锁释放后，上报失败资源及原始原因；其他失败作为 suppressed 异常保留。 */
@Getter
public class ContentResourceWarmupException extends RuntimeException {
    private final ContentResourceRegion region;

    public ContentResourceWarmupException(ContentResourceRegion region, Throwable cause) {
        super("Content resource warmup failed region=" + region, cause);
        this.region = region;
    }
}
