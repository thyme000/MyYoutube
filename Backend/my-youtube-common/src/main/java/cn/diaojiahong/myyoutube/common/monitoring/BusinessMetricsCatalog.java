package cn.diaojiahong.myyoutube.common.monitoring;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BusinessMetricsCatalog implements BeanPostProcessor, SmartInitializingSingleton {
    private final ObjectProvider<BusinessMetrics> metrics;
    private final Set<BusinessOperation> operations = ConcurrentHashMap.newKeySet();

    public BusinessMetricsCatalog(ObjectProvider<BusinessMetrics> metrics) { this.metrics = metrics; }

    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        Class<?> type = AopUtils.getTargetClass(bean);
        if (type.getPackageName().startsWith("cn.diaojiahong.myyoutube")) ReflectionUtils.doWithMethods(type, method -> {
            var observed = AnnotatedElementUtils.findMergedAnnotation(method, ObservedBusiness.class);
            if (observed != null) operations.add(observed.value());
        });
        return bean;
    }

    @Override public void afterSingletonsInstantiated() {
        operations.forEach(metrics.getObject()::registerOperation);
    }
}
