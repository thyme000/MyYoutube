package cn.diaojiahong.myyoutube.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** 业务服务(main 类在 cn.diaojiahong.myyoutube 根包)自动加载; 网关扫描不到, 自行 init */
@Configuration
public class JwtSecretConfig {

    public JwtSecretConfig(@Value("${jwt.secret}") String secret) {
        JwtUtil.init(secret);
    }
}
