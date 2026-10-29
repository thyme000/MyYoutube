package cn.diaojiahong.myyoutube.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AdminWebConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<AdminAuthorization> authorization;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // 请求时再获取，避免 MVC 初始化与角色 Feign 客户端创建相互依赖。
                authorization.getObject().requireAdmin();
                return true;
            }
        }).addPathPatterns("/admin", "/admin/**");
    }
}
