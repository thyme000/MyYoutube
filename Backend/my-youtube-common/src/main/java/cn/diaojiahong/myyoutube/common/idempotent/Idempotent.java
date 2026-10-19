package cn.diaojiahong.myyoutube.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis 执行占位与短窗口完成缓存；业务持久化结果仍由调用方校验。
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

    /** 成功结果缓存秒数；0 表示仅在执行期间互斥。 */
    long ttl() default 300;

    /** 执行占位秒数，执行期间自动续期。 */
    long leaseSeconds() default IdempotentGuard.DEFAULT_LEASE_SECONDS;

    /** 重复请求的处置策略 */
    DuplicatePolicy policy() default DuplicatePolicy.THROW;
}
