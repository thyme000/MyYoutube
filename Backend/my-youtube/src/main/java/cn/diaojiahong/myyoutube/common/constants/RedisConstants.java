package cn.diaojiahong.myyoutube.common.constants;

public class RedisConstants {

    // 用户登录token凭证
    public static final String USER_TOKEN = "user:token:";
    public static final Long USER_TOKEN_EXPIRE = 2L;

    // 验证码
    public static final String CAPTCHA_LOGIN = "captcha:login:";
    public static final String CAPTCHA_REGISTER = "captcha:register:";
    public static final Long CAPTCHA_EXPIRE = 60L;
    public static final String CAPTCHA_FORGET = "captcha:forget:";
}
