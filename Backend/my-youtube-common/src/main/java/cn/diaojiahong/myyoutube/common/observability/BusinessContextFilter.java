package cn.diaojiahong.myyoutube.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 入口侧补齐业务关联: 把上游(Feign 透传的 X-* 头)带来的业务标识写进 MDC,
 * 使本进程处理该请求期间的所有日志都带上同一组字段。无关联头时不做任何事。
 * 值与 {@link BusinessContext} 一样按白名单字符校验, 不含空白符, 不会污染日志行。
 */
@Component
public class BusinessContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> fields = new HashMap<>();
        BusinessContext.HEADERS.forEach((key, header) -> {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                fields.put(key, value);
            }
        });
        if (fields.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        try (var ignored = BusinessContext.open(fields)) {
            chain.doFilter(request, response);
        }
    }
}
