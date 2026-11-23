package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;

/** 已有请求持有资源加载锁；预热可直接跳过，普通请求仍返回 503。 */
public class ContentResourceBusyException extends DependencyException {
    public ContentResourceBusyException() {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容资源正在加载，请稍后重试", null);
    }
}
