package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.monitoring.DeadLetterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.util.Map;
import static cn.diaojiahong.myyoutube.common.constants.MqConstants.*;

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
                Map.entry(ingestGroup, TOPIC_PIPELINE_EVENTS),
                Map.entry("transcode_draft_worker", TOPIC_MEDIA_TASK),
                Map.entry("transcode_content_worker", TOPIC_MEDIA_TASK),
                Map.entry("transcode_review_worker", TOPIC_MEDIA_TASK),
                Map.entry("transcode_content_submitted", TOPIC_CONTENT_SUBMITTED),
                Map.entry("transcode_review_requested", TOPIC_REVIEW_REQUESTED),
                Map.entry("transcode_media_upload_event_group", TOPIC_MEDIA_UPLOAD_EVENT),
                Map.entry("video_content_understood", TOPIC_CONTENT_UNDERSTOOD),
                Map.entry("video_review_result", TOPIC_REVIEW_RESULT),
                Map.entry("video_audio_content_ban", TOPIC_AUDIO_CONTENT_BAN),
                Map.entry("video_publication_search", TOPIC_PUBLICATION_EFFECT),
                Map.entry("video_publication_notice", TOPIC_PUBLICATION_EFFECT),
                Map.entry("video_publication_audio_count", TOPIC_PUBLICATION_EFFECT));
    }

    @Bean
    @ConditionalOnProperty(name="my-youtube.monitor.logs-enabled", havingValue="true", matchIfMissing=true)
    FilebeatMetrics filebeatMetrics(MonitorProperties monitor, IngestProperties ingest, MeterRegistry registry) {
        return new FilebeatMetrics(monitor, ingest, registry, Clock.systemUTC());
    }
}
