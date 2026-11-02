package cn.diaojiahong.myyoutube.security;

/** JWT 验签后供凭证作废校验使用的用户与签发时间。 */
public record JwtPayload(long userId, long issuedAtMillis) {
}
