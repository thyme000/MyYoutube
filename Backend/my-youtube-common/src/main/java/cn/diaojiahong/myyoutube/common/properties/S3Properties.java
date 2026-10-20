package cn.diaojiahong.myyoutube.common.properties;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "my-youtube.s3")
@Data
public class S3Properties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    /** SigV4 签名域, 仅参与签名自洽, RustFS/MinIO 不校验具体值 */
    private String region = "us-east-1";

    @PostConstruct
    void validate() {
        require(endpoint, "endpoint");
        require(accessKey, "access-key");
        require(secretKey, "secret-key");
        require(bucketName, "bucket-name");
    }

    private static void require(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("缺少必需配置：my-youtube.s3." + name);
        }
    }
}
