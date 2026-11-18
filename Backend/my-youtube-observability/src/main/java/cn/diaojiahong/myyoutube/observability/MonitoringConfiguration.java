package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.monitoring.DeadLetterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.util.Map;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.common.observability.RocketMqOutboxPublisher;
import cn.diaojiahong.myyoutube.common.mq.message.ContentSubmittedMessage;
import cn.diaojiahong.myyoutube.common.mq.message.ContentUnderstoodMessage;
import cn.diaojiahong.myyoutube.common.mq.message.ReviewRequestedMessage;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;

@Configuration
@EnableConfigurationProperties(MonitorProperties.class)
@ConditionalOnProperty(name="my-youtube.monitor.enabled", havingValue="true")
@Import(DeadLetterMetrics.class)
public class MonitoringConfiguration {
    @Bean(initMethod="start", destroyMethod="shutdown")
    DefaultMQProducer monitoringAdmin(IngestProperties properties) {
        var admin = new DefaultMQProducer("pipeline_monitor_" + ProcessHandle.current().pid());
        admin.setNamesrvAddr(properties.getNameserver());
        admin.setVipChannelEnabled(false);
        return admin;
    }

    @Bean
    MqMetrics mqMetrics(
            MonitorProperties properties,
            IngestProperties ingest,
            DefaultMQProducer monitoringAdmin,
            MeterRegistry registry) {
        if (properties.getGroups().isEmpty()) properties.setGroups(defaultGroups(ingest.getGroup()));
        return new MqMetrics(properties, monitoringAdmin, registry);
    }

    static Map<String, String> defaultGroups(String ingestGroup) {
        return Map.ofEntries(
                Map.entry(ingestGroup, RocketMqOutboxPublisher.TOPIC),
                Map.entry("transcode_draft_worker", MediaTaskMessage.TOPIC),
                Map.entry("transcode_content_worker", MediaTaskMessage.TOPIC),
                Map.entry("transcode_review_worker", MediaTaskMessage.TOPIC),
                Map.entry("transcode_content_submitted", ContentSubmittedMessage.TOPIC),
                Map.entry("transcode_review_requested", ReviewRequestedMessage.TOPIC),
                Map.entry("transcode_media_upload_event_group", MediaUploadEventMessage.TOPIC),
                Map.entry("video_content_understood", ContentUnderstoodMessage.TOPIC),
                Map.entry("video_review_result", PipelineContracts.ReviewResult.TOPIC),
                Map.entry("video_audio_content_ban", AudioContentBanMessage.TOPIC),
                Map.entry("video_publication_search", PublicationEffectMessage.TOPIC),
                Map.entry("video_publication_notice", PublicationEffectMessage.TOPIC),
                Map.entry("video_publication_audio_count", PublicationEffectMessage.TOPIC));
    }

    @Bean
    @ConditionalOnProperty(name="my-youtube.monitor.logs-enabled", havingValue="true", matchIfMissing=true)
    FilebeatMetrics filebeatMetrics(MonitorProperties monitor, IngestProperties ingest, MeterRegistry registry) {
        return new FilebeatMetrics(monitor, ingest, registry, Clock.systemUTC());
    }
}
