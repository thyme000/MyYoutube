package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import java.util.Map;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(IngestProperties.class)
public class ObservationApplication {
    public static void main(String[] args) { SpringApplication.run(ObservationApplication.class, args); }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="my-youtube.ingest.enabled", havingValue="true", matchIfMissing=true)
    ElasticEventStore eventStore(IngestProperties properties, MeterRegistry metrics) {
        ElasticEventStore store = new ElasticEventStore(properties, Clock.systemUTC(), metrics);
        store.initialize();
        return store;
    }
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="my-youtube.ingest.enabled", havingValue="true", matchIfMissing=true)
    DefaultMQPushConsumer eventConsumer(IngestProperties properties, ElasticEventStore store, MeterRegistry metrics) throws Exception {
        DefaultMQProducer admin = new DefaultMQProducer(properties.getGroup() + "_topology");
        admin.setNamesrvAddr(properties.getNameserver());
        admin.setVipChannelEnabled(false);
        try {
            admin.start();
            admin.createTopic("TBW102", MqConstants.TOPIC_PIPELINE_EVENTS, 4, Map.of());
        } finally { admin.shutdown(); }
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(properties.getGroup());
        consumer.setNamesrvAddr(properties.getNameserver());
        consumer.setInstanceName("pipeline-ingest-" + ProcessHandle.current().pid());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(2);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(properties.getMaxReconsumeTimes());
        consumer.subscribe(MqConstants.TOPIC_PIPELINE_EVENTS, "*");
        PendingDlq pending = new PendingDlq(properties.getMarkerDirectory(), properties.getGroup());
        consumer.registerMessageListener(new BatchConsumer(store::ingest, message -> {
            // 高层 sendMessageBack 在 RPC 失败时会静默退化为重试消息。
            consumer.getDefaultMQPushConsumerImpl().getmQClientFactory().getMQClientAPIImpl().consumerSendMessageBack(
                    RemotingHelper.parseSocketAddressAddr(message.getStoreHost()), message.getBrokerName(), message,
                    properties.getGroup(), -1, 5000L, properties.getMaxReconsumeTimes());
        }, pending, metrics));
        return consumer;
    }
}
