package cn.diaojiahong.myyoutube.video.config;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.FilterRule;
import software.amazon.awssdk.services.s3.model.FilterRuleName;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.NotificationConfigurationFilter;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3KeyFilter;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "my-youtube.s3.video-upload-notification-enabled", havingValue = "true")
public class VideoUploadNotificationJob {

    private static final String RULE_ID = "video-upload";
    private final S3Client client;
    private final S3Properties properties;
    private final QueueConfiguration expected;

    public VideoUploadNotificationJob(S3Client client, S3Properties properties,
                                      @Value("${my-youtube.s3.video-upload-notification-queue-arn}") String queueArn) {
        this.client = client;
        this.properties = properties;
        this.expected = QueueConfiguration.builder()
                .id(RULE_ID)
                .queueArn(queueArn)
                .events(Event.S3_OBJECT_CREATED)
                .filter(NotificationConfigurationFilter.builder()
                        .key(S3KeyFilter.builder()
                                .filterRules(FilterRule.builder().name(FilterRuleName.PREFIX).value("uploads/video/").build())
                                .build())
                        .build())
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${my-youtube.s3.video-upload-notification-check-interval-ms:60000}",
            initialDelayString = "${my-youtube.s3.video-upload-notification-check-interval-ms:60000}")
    public void reconcile() {
        String bucket = properties.getBucketName();
        try {
            GetBucketNotificationConfigurationResponse current;
            var getRequest = GetBucketNotificationConfigurationRequest.builder().bucket(bucket).build();
            try {
                current = client.getBucketNotificationConfiguration(getRequest);
            } catch (S3Exception error) {
                if (error.statusCode() != 404) throw error;
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                current = client.getBucketNotificationConfiguration(getRequest);
            }

            var ownedRules = current.queueConfigurations().stream()
                    .filter(rule -> RULE_ID.equals(rule.id())).toList();
            if (ownedRules.size() == 1 && matchesExpected(ownedRules.get(0))) return;
            var queues = new ArrayList<>(current.queueConfigurations());
            queues.removeIf(queue -> RULE_ID.equals(queue.id()));
            queues.add(expected);
            var notification = NotificationConfiguration.builder()
                    .queueConfigurations(queues)
                    .topicConfigurations(current.topicConfigurations())
                    .lambdaFunctionConfigurations(current.lambdaFunctionConfigurations());
            if (current.eventBridgeConfiguration() != null)
                notification.eventBridgeConfiguration(current.eventBridgeConfiguration());
            client.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
                    .bucket(bucket).notificationConfiguration(notification.build()).build());
            log.info("已修复视频上传桶通知: bucket={}, rule={}", bucket, RULE_ID);
        } catch (Exception error) {
            log.warn("视频上传桶通知检查失败: bucket={}", bucket, error);
        }
    }

    private boolean matchesExpected(QueueConfiguration rule) {
        if (!RULE_ID.equals(rule.id()) || !expected.queueArn().equals(rule.queueArn())
                || !rule.eventsAsStrings().equals(List.of("s3:ObjectCreated:*"))) return false;
        var filter = rule.filter();
        if (filter == null || filter.key() == null || filter.key().filterRules().size() != 1) return false;
        var prefix = filter.key().filterRules().get(0);
        return "prefix".equalsIgnoreCase(prefix.nameAsString()) && "uploads/video/".equals(prefix.value());
    }
}
