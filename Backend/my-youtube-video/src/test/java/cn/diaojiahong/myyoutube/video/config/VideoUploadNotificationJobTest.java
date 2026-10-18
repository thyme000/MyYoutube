package cn.diaojiahong.myyoutube.video.config;

import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.TopicConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoUploadNotificationJobTest {

    private static final String QUEUE_ARN = "arn:rustfs:sqs::primary:webhook";
    private final S3Client client = mock(S3Client.class);
    private final S3Properties properties = new S3Properties();
    private final VideoUploadNotificationJob job;

    VideoUploadNotificationJobTest() {
        properties.setBucketName("myyoutube");
        job = new VideoUploadNotificationJob(client, properties, QUEUE_ARN);
    }

    @Test
    void installsRuleWithoutRemovingOtherNotifications() {
        var otherQueue = QueueConfiguration.builder().id("other").queueArn("arn:rustfs:sqs::other:webhook")
                .events(Event.S3_OBJECT_REMOVED).build();
        var topic = TopicConfiguration.builder().id("topic").topicArn("arn:aws:sns:::topic")
                .events(Event.S3_OBJECT_REMOVED).build();
        when(client.getBucketNotificationConfiguration(any(GetBucketNotificationConfigurationRequest.class)))
                .thenReturn(GetBucketNotificationConfigurationResponse.builder()
                        .queueConfigurations(otherQueue).topicConfigurations(topic).build());

        job.reconcile();

        var request = capturePut();
        var config = request.notificationConfiguration();
        assertEquals("myyoutube", request.bucket());
        assertEquals(topic, config.topicConfigurations().get(0));
        assertEquals(otherQueue, config.queueConfigurations().get(0));
        var rule = config.queueConfigurations().get(1);
        assertEquals("video-upload", rule.id());
        assertEquals(QUEUE_ARN, rule.queueArn());
        assertEquals("s3:ObjectCreated:*", rule.eventsAsStrings().get(0));
        assertEquals("uploads/video/", rule.filter().key().filterRules().get(0).value());
    }

    @Test
    void leavesMatchingRuleUntouched() {
        when(client.getBucketNotificationConfiguration(any(GetBucketNotificationConfigurationRequest.class)))
                .thenReturn(GetBucketNotificationConfigurationResponse.builder().build());
        job.reconcile();
        var installed = capturePut().notificationConfiguration().queueConfigurations().get(0);
        clearInvocations(client);
        when(client.getBucketNotificationConfiguration(any(GetBucketNotificationConfigurationRequest.class)))
                .thenReturn(GetBucketNotificationConfigurationResponse.builder().queueConfigurations(installed).build());

        job.reconcile();

        verify(client, never()).putBucketNotificationConfiguration(any(PutBucketNotificationConfigurationRequest.class));
    }

    @Test
    void replacesOnlyOutdatedOwnedRule() {
        var oldRule = QueueConfiguration.builder().id("video-upload").queueArn("arn:rustfs:sqs::old:webhook")
                .events(Event.S3_OBJECT_CREATED).build();
        var otherRule = QueueConfiguration.builder().id("other").queueArn("arn:rustfs:sqs::other:webhook")
                .events(Event.S3_OBJECT_REMOVED).build();
        when(client.getBucketNotificationConfiguration(any(GetBucketNotificationConfigurationRequest.class)))
                .thenReturn(GetBucketNotificationConfigurationResponse.builder()
                        .queueConfigurations(oldRule, otherRule).build());

        job.reconcile();

        var rules = capturePut().notificationConfiguration().queueConfigurations();
        assertEquals(2, rules.size());
        assertEquals(otherRule, rules.get(0));
        assertEquals(QUEUE_ARN, rules.get(1).queueArn());
    }

    @Test
    void recreatesDeletedBucketAndItsRule() {
        when(client.getBucketNotificationConfiguration(any(GetBucketNotificationConfigurationRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build())
                .thenReturn(GetBucketNotificationConfigurationResponse.builder().build());

        job.reconcile();

        verify(client).createBucket(argThat((software.amazon.awssdk.services.s3.model.CreateBucketRequest request) ->
                "myyoutube".equals(request.bucket())));
        assertEquals("video-upload", capturePut().notificationConfiguration().queueConfigurations().get(0).id());
    }

    private PutBucketNotificationConfigurationRequest capturePut() {
        var captor = org.mockito.ArgumentCaptor.forClass(PutBucketNotificationConfigurationRequest.class);
        verify(client).putBucketNotificationConfiguration(captor.capture());
        return captor.getValue();
    }
}
