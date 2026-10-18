package cn.diaojiahong.myyoutube.video.config;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoUploadNotificationRustFsTest {

    @Test
    void reconcilesLocalRustFsBucket() {
        assumeTrue("true".equals(System.getenv("RUN_RUSTFS_NOTIFICATION_INTEGRATION")));
        var properties = new S3Properties();
        properties.setBucketName(System.getenv("S3_TEST_BUCKET"));
        try (var client = S3Client.builder()
                .endpointOverride(URI.create(System.getenv("S3_TEST_ENDPOINT")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("S3_TEST_ACCESS_KEY"), System.getenv("S3_TEST_SECRET_KEY"))))
                .build()) {
            var observedClient = spy(client);
            var request = GetBucketNotificationConfigurationRequest.builder().bucket(properties.getBucketName()).build();
            var before = observedClient.getBucketNotificationConfiguration(request);
            var job = new VideoUploadNotificationJob(observedClient, properties, "arn:rustfs:sqs::primary:webhook");
            job.reconcile();
            var after = observedClient.getBucketNotificationConfiguration(request);
            assertEquals(before.topicConfigurations(), after.topicConfigurations());
            assertEquals(before.lambdaFunctionConfigurations(), after.lambdaFunctionConfigurations());
            assertEquals(before.queueConfigurations().stream().filter(rule -> !"video-upload".equals(rule.id())).toList(),
                    after.queueConfigurations().stream().filter(rule -> !"video-upload".equals(rule.id())).toList());
            assertTrue(after.queueConfigurations().stream().anyMatch(rule -> "video-upload".equals(rule.id())
                    && "arn:rustfs:sqs::primary:webhook".equals(rule.queueArn())
                    && rule.eventsAsStrings().contains("s3:ObjectCreated:*")
                    && rule.filter() != null && rule.filter().key() != null
                    && rule.filter().key().filterRules().stream().anyMatch(filter -> "prefix".equalsIgnoreCase(filter.nameAsString())
                    && "uploads/video/".equals(filter.value()))));
            clearInvocations(observedClient);
            job.reconcile();
            verify(observedClient, never()).putBucketNotificationConfiguration(any(PutBucketNotificationConfigurationRequest.class));
        }
    }
}
