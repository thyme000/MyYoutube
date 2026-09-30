package cn.diaojiahong.myyoutube.security;

/** 验签通过后的 access token 载荷 */
public record JwtPayload(long userId, long issuedAtMillis) {
}
