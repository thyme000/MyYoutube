package cn.diaojiahong.myyoutube.common.filter;

import cn.diaojiahong.myyoutube.common.utils.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * 微服务模式下从网关透传的 X-User-Id 头还原登录用户;
 * 过渡期旧单体仍在用 LoginInterceptor 验 token, 本过滤器与其共存(同值覆写, 无副作用)
 */
@Component
public class UserIdHeaderFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId != null && !userId.isBlank()) {
            UserContext.setUser(userId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.removeUser();
        }
    }
}
