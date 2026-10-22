package cn.diaojiahong.myyoutube.common.filter;

import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * 微服务模式下从网关透传的 X-User-Id 头还原登录用户;
 * 配置了内部密钥时要求网关附带的 X-Internal-Token 匹配, 拒绝绕网关直连伪造的身份。
 */
@Slf4j
@Component
public class UserIdHeaderFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public UserIdHeaderFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static final String USER_ID_HEADER = AuthConstants.USER_ID_HEADER;
    public static final String INTERNAL_TOKEN_HEADER = AuthConstants.INTERNAL_TOKEN_HEADER;

    @Value("${my-youtube.internal-token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId != null && !userId.isBlank()) {
            if (!internalToken.isBlank() && !internalToken.equals(request.getHeader(INTERNAL_TOKEN_HEADER))) {
                // 只记路径: query 里可能带长连接凭证
                log.warn("拒绝绕过网关的身份透传 method={} path={}", request.getMethod(), request.getRequestURI());
                response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus());
                response.setContentType("application/json;charset=UTF-8");
                objectMapper.writeValue(response.getOutputStream(), Result.fail(ErrorCode.UNAUTHORIZED, "非法请求来源"));
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
