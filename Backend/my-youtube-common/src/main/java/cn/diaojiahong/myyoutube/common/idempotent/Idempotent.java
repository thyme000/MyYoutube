package cn.diaojiahong.myyoutube.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 短窗口幂等: Redis 占位防重复。不提供跨重启能力, 持久化幂等走任务表+唯一约束。
 * 切面在事务外层, 故占位先于开事务、失败释放发生在回滚之后。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * SpEL 幂等键(拼在 idempotent: 前缀后), 可引用方法参数(#dto)与 #userId。
     * 解析为空直接抛异常 —— 静默回退成固定 key 会把所有请求误判为重复。
     */
    String key();

    /** 占位存活秒数, 必须大于方法最坏执行耗时, 否则占位提前过期防不住并发 */
    long ttl() default 300;

    /** 重复请求的处置策略 */
    DuplicatePolicy policy() default DuplicatePolicy.THROW;
}
