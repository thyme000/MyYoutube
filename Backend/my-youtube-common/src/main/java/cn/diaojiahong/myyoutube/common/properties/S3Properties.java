package cn.diaojiahong.myyoutube.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "my-youtube.s3")
@Data
public class S3Properties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;

}
