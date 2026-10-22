package cn.diaojiahong.myyoutube.common.feign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import static org.assertj.core.api.Assertions.assertThat;

class FeignIsolationTest {
    @Test void servicesWithoutFeignDoNotLoadFeignAdviceOrConfiguration() {
        new ApplicationContextRunner().withClassLoader(new FilteredClassLoader("feign"))
                .withUserConfiguration(Scan.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FeignExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(InternalFeignConfiguration.class);
                });
    }
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = FeignExceptionHandler.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = FeignExceptionHandler.class))
    static class Scan {}
}
