package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.Interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final StringRedisTemplate redisTemplate;


    /**
     * 将LoginInterceptor注册到spring MVC中,并配置拦截路径
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(redisTemplate))
                .addPathPatterns("/**")          // 拦截所有请求
                .excludePathPatterns(            // 放行不需要登录的接口
                        "/login",
                        "/register",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/captcha",
                        "/forget-password",
                        "/internal/**"   // 服务间内部接口, 不经网关暴露
                );
    }
}
