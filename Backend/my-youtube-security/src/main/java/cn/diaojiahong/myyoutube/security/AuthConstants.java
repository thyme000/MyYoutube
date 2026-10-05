package cn.diaojiahong.myyoutube.security;

/**
 * 鉴权协议常量单点: 网关(GatewayAuthFilter)与各服务(UserIdHeaderFilter/RedisConstants)
 * 共用的头名与 Redis key 前缀。两边曾各写一份字面量, 改名会静默失联。
 */
public final class AuthConstants {

    private AuthConstants() {
    }

    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /** 网关验签后透传的用户身份头, 各服务经 UserIdHeaderFilter 还原登录态 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** 网关附带内部凭证, 服务侧校验请求确来自网关 */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** 用户 token 版本号前缀: 改密/封号后作废旧凭证 */
    public static final String TOKEN_VER_KEY_PREFIX = "user:token-ver:";

    /** 长连接一次性连接票前缀: chat 换票端点写入, 网关 GETDEL 消费 */
    public static final String PUSH_TICKET_KEY_PREFIX = "push:ticket:";
}
