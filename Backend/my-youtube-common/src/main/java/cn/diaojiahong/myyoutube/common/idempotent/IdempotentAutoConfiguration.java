package cn.diaojiahong.myyoutube.common.idempotent;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 与 ChainAutoConfiguration 同理: 网关主类不落在根包扫不到 common 组件, 统一经自动装配暴露。
 * 网关侧无 @Idempotent 方法, 切面装配后空转无害。
 */
@AutoConfiguration
public class IdempotentAutoConfiguration {

    @Bean
    public IdempotentAspect idempotentAspect(StringRedisTemplate redis) {
        return new IdempotentAspect(redis);
    }
}
