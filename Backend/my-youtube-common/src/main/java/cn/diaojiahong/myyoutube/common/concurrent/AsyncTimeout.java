package cn.diaojiahong.myyoutube.common.concurrent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 通过 Spring 代理调用；被标注方法返回 CompletableFuture，调用方自行决定失败时的降级。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsyncTimeout {
    String value();
}
