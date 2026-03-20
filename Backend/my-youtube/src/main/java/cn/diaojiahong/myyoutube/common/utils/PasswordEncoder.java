package cn.diaojiahong.myyoutube.common.utils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;


public class PasswordEncoder {

    // BCryptPasswordEncoder 是线程安全的（内部无可变状态），可复用
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    // 注册时：把明文密码转为哈希，存数据库
    public static String encode(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    // 登录时：用明文去匹配数据库里存的哈希
    public static boolean matches(String rawPassword, String hashedPassword) {
        return ENCODER.matches(rawPassword, hashedPassword);
    }

}
