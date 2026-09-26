package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class S3ClientConfig {

    private final S3Properties s3Properties;

    // region 对 S3 协议必填, 对 RustFS/MinIO 无实际意义
    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(s3Properties.getEndpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(credentialsProvider())
                .build();
    }

    // 预签名 URL 的 host 必须与实际访问方式一致，这里同为路径风格
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(s3Properties.getEndpoint()))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentialsProvider())
                .build();
    }

    // 启动时确保桶存在; 存储不可用只告警, 不阻断应用启动
    @Bean
    ApplicationRunner ensureBucket(S3Client client) {
        String bucket = s3Properties.getBucketName();
        return args -> {
            try {
                client.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                client.createBucket(c -> c.bucket(bucket));
            } catch (Exception e) {
                log.warn("存储服务不可用, 跳过建桶检查: {}", e.getMessage());
            }
        };
    }

    private StaticCredentialsProvider credentialsProvider() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3Properties.getAccessKey(), s3Properties.getSecretKey()));
    }
}
