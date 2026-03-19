package cn.diaojiahong.myyoutube.common.utils;

import java.security.SecureRandom;

/**
 * 用于生成验证码
 */
public class CaptchaUtils {

    // SecureRandom 是密码学安全的随机数生成器，适合验证码场景
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // 生成纯数字验证码（短信/登录用）
    public static String generateNumCaptcha(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
