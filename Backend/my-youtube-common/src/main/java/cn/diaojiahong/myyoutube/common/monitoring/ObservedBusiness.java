package cn.diaojiahong.myyoutube.common.monitoring;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ObservedBusiness {
    BusinessOperation value();
}
