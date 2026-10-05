package cn.diaojiahong.myyoutube.common.chain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * security 是网关与服务共享的内核模块, 但网关主类不落在根包扫不到这里的组件,
 * 统一经自动装配暴露 —— 各服务与网关无需任何扫描配置即可拿到注册表。
 */
@AutoConfiguration
public class ChainAutoConfiguration {

    @Bean
    public ChainStepRegistry chainStepRegistry(List<ChainStep<?>> steps) {
        return new ChainStepRegistry(steps);
    }
}
