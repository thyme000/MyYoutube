package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.common.properties.AliOssProperties;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OssClientConfig {

    private final AliOssProperties aliOssProperties;

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient() {

        return new OSSClientBuilder().build(
                aliOssProperties.getEndpoint(),
                aliOssProperties.getAccessKeyId(),
                aliOssProperties.getAccessKeySecret()
        );
    }
}
