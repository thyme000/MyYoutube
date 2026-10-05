package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.security.JwtPayload;
import org.springframework.web.server.ServerWebExchange;

/**
 * 鉴权链上下文: 请求分类(归一化路径/是否长连接)由编排器填充,
 * 各步骤按序检查并写回决策 —— CONTINUE 表示本步骤未终结交给下一步。
 */
public class AuthContext {

    public enum Decision {
        /** 还没有步骤做决定, 继续走链 */
        CONTINUE,
        /** 免鉴权白名单, 原样放行(不透传身份头) */
        SKIP,
        /** 凭证有效, 改写请求透传 userId 后放行 */
        AUTHENTICATED,
        /** 凭证被识别但被拒绝(缺失/无效/已作废), 统一 401 */
        REJECT
    }

    private final ServerWebExchange exchange;
    private final String path;
    private final boolean connectionPath;
    private Decision decision = Decision.CONTINUE;
    private Long principalUserId;
    private JwtPayload jwtPayload;

    private AuthContext(ServerWebExchange exchange, String path, boolean connectionPath) {
        this.exchange = exchange;
        this.path = path;
        this.connectionPath = connectionPath;
    }

    public static AuthContext initial(ServerWebExchange exchange, String normalizedPath, boolean connectionPath) {
        return new AuthContext(exchange, normalizedPath, connectionPath);
    }

    /** 链上还有步骤需要执行吗 */
    public boolean decided() {
        return decision != Decision.CONTINUE;
    }

    public void skip() {
        this.decision = Decision.SKIP;
    }

    public void authenticated(long userId) {
        this.principalUserId = userId;
        this.decision = Decision.AUTHENTICATED;
    }

    public void reject() {
        this.decision = Decision.REJECT;
    }

    /** Bearer 步骤验签通过后暂存载荷, 作废校验步骤接着消费 */
    public void jwtPayload(JwtPayload payload) {
        this.jwtPayload = payload;
    }

    public ServerWebExchange getExchange() {
        return exchange;
    }

    public String getPath() {
        return path;
    }

    public boolean isConnectionPath() {
        return connectionPath;
    }

    public Decision getDecision() {
        return decision;
    }

    public Long getPrincipalUserId() {
        return principalUserId;
    }

    public JwtPayload getJwtPayload() {
        return jwtPayload;
    }
}
