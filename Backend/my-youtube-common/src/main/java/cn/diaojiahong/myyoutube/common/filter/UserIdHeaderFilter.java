package cn.diaojiahong.myyoutube.common.filter;

import cn.diaojiahong.myyoutube.common.utils.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * 微服务模式下从网关透传的 X-User-Id 头还原登录用户;
 * 配置了内部密钥时要求网关附带的 X-Internal-Token 匹配, 拒绝绕网关直连伪造的身份。
 */
@Component
public class UserIdHeaderFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Value("${my-youtube.internal-token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId != null && !userId.isBlank()) {
            if (!internalToken.isBlank() && !internalToken.equals(request.getHeader(INTERNAL_TOKEN_HEADER))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"非法请求来源\",\"data\":null}");
                return;
            }
            UserContext.setUser(userId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.removeUser();
        }
    }
}
