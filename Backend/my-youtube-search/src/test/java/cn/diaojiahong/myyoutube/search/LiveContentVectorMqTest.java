package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.mq.listener.ContentVectorListener;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorPublisher;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorDeadLetters;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorDeadLetterService;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "vector.mq.integration", matches = "true")
class LiveContentVectorMqTest {
    private static final String NAMESERVER = "127.0.0.1:19876";
    private static final String ES_ADDRESS = "127.0.0.1:19201";

    @Test void brokerRetainsTasksRetriesFailuresAndPreservesDeadLettersAcrossConsumerRestarts() throws Exception {
        var client = ElasticsearchClients.createImperative(ClientConfiguration.builder().connectedTo(ES_ADDRESS).build());
        var suffix = UUID.randomUUID().toString().replace("-", "");
        String group = MqConstants.GROUP_CONTENT_VECTOR;
        long recoverableId = System.currentTimeMillis();
        long deadLetterId = recoverableId + 1, privateId = recoverableId + 2, restartId = recoverableId + 3;
        var template = new RocketMQTemplate();
        var probe = new DefaultMQPullConsumer("vector_probe_" + suffix);
        var deadLetters = new ContentVectorDeadLetters(NAMESERVER, new ObjectMapper());
        DefaultRocketMQListenerContainer consumer = null;
        try {
            assertEquals("content-it", client.info().clusterName());
            var operations = new ElasticsearchTemplate(client);
            var index = operations.indexOps(ContentDoc.class);
            if (index.exists()) index.delete();
            index.createWithMapping();
            var repository = new ElasticsearchRepositoryFactory(operations).getRepository(ContentDocRepository.class);
            var producer = new DefaultMQProducer("vector_producer_" + suffix);
            producer.setNamesrvAddr(NAMESERVER); producer.setVipChannelEnabled(false);
            template.setProducer(producer);
            template.setMessageConverter(new RocketMQMessageConverter().getMessageConverter());
            template.afterPropertiesSet();
            producer.createTopic("TBW102", MqConstants.TOPIC_CONTENT_VECTOR, 1, Map.of());
            var properties = new VectorProperties();
            var publisher = new ContentVectorPublisher(template, properties);
            var writer = new ContentDocumentWriter(operations, mock(UserPort.class), publisher);
            var attempts = new ConcurrentHashMap<String, AtomicInteger>();
            var deliveries = new ConcurrentHashMap<Long, AtomicInteger>();
            var llm = mock(LlmPort.class);
            var failPersistentTask = new AtomicBoolean(true);
            var vector = new java.util.ArrayList<>(Collections.nCopies(4096, 0.0f)); vector.set(0, 1.0f);
            when(llm.embeddings(any())).thenAnswer(call -> {
                String text = call.<LlmPort.EmbeddingsRequest>getArgument(0).getTexts().get(0);
                int attempt = attempts.computeIfAbsent(text, key -> new AtomicInteger()).incrementAndGet();
                if (text.equals("暂时失败\n简介\n摘要") && attempt == 1) throw new IllegalStateException("temporary model failure");
                if (text.equals("永久失败\n简介\n摘要") && failPersistentTask.get()) throw new IllegalStateException("persistent model failure");
                return new LlmPort.EmbeddingsResponse(List.of(vector), 4096, "vector-it:4096");
            });
            var vectors = spy(new ContentVectorService(llm, client, mock(StringRedisTemplate.class), properties,
                    mock(ThreadPoolTaskExecutor.class)));
            doAnswer(call -> {
                deliveries.computeIfAbsent(call.getArgument(0), key -> new AtomicInteger()).incrementAndGet();
                return call.callRealMethod();
            }).when(vectors).embedContent(anyLong());
            var listener = new ContentVectorListener(vectors);

            writer.write(document(recoverableId, "暂时失败"));
            assertTrue(repository.existsById(recoverableId));
            assertNull(repository.findById(recoverableId).orElseThrow().getVector());
            consumer = startConsumer(group, listener);
            await(() -> "vector-it:4096".equals(repository.findById(recoverableId).orElseThrow().getVectorVersion()), "queued task must survive until a consumer starts and retry successfully");
            assertEquals(2, attempts.get("暂时失败\n简介\n摘要").get());
            publisher.publish(recoverableId);
            await(() -> deliveries.get(recoverableId).get() >= 3, "duplicate task must be consumed");
            assertEquals(2, attempts.get("暂时失败\n简介\n摘要").get());

            probe.setNamesrvAddr(NAMESERVER); probe.setVipChannelEnabled(false); probe.start();
            writer.write(document(deadLetterId, "永久失败"));
            await(() -> deadLetterContains(probe, group, deadLetterId), "exhausted task must be retained in DLQ");
            assertEquals(4, attempts.get("永久失败\n简介\n摘要").get());
            assertNull(repository.findById(deadLetterId).orElseThrow().getVector());

            var recovery = new ContentVectorDeadLetterService(deadLetters, client, publisher, properties);
            ContentVectorDeadLetterPort.Item failed = recoveryItem(recovery, deadLetterId);
            assertEquals(ContentVectorDeadLetterPort.State.UNRECOVERED, failed.state());
            assertEquals(failed.reference(), recoveryItem(recovery, deadLetterId).reference());
            assertTrue(deadLetterContains(probe, group, deadLetterId));
            failPersistentTask.set(false);
            assertEquals(ContentVectorDeadLetterPort.ReplayStatus.ACCEPTED, recovery.replay(failed.reference(), 1L).status());
            await(() -> "vector-it:4096".equals(repository.findById(deadLetterId).orElseThrow().getVectorVersion()), "manual replay must recover the vector");
            assertEquals(5, attempts.get("永久失败\n简介\n摘要").get());
            assertEquals(ContentVectorDeadLetterPort.ReplayStatus.ALREADY_COMPLETED, recovery.replay(failed.reference(), 1L).status());
            assertEquals(ContentVectorDeadLetterPort.State.RECOVERED, recoveryItem(recovery, deadLetterId).state());
            assertTrue(deadLetterContains(probe, group, deadLetterId));

            consumer.destroy(); consumer = null;
            var privateDocument = document(privateId, "已转私密"); writer.write(privateDocument);
            privateDocument.setVisibilityCode("private"); writer.write(privateDocument);
            writer.write(document(restartId, "重启恢复"));
            consumer = startConsumer(group, listener);
            await(() -> "vector-it:4096".equals(repository.findById(restartId).orElseThrow().getVectorVersion()), "queued work must resume after consumer restart");
            assertTrue(deliveries.containsKey(privateId));
            assertFalse(attempts.containsKey("已转私密\n简介\n摘要"));
            assertEquals("private", repository.findById(privateId).orElseThrow().getVisibility());
            assertNull(repository.findById(privateId).orElseThrow().getVector());
        } finally {
            if (consumer != null) consumer.destroy();
            deadLetters.close(); probe.shutdown(); template.destroy(); client._transport().close();
        }
    }

    private ContentVectorDeadLetterPort.Item recoveryItem(ContentVectorDeadLetterService service, Long contentId) {
        String cursor = null;
        do {
            ContentVectorDeadLetterPort.Page page = service.query(cursor, 50);
            for (ContentVectorDeadLetterPort.Item item : page.items()) {
                if (contentId.equals(item.contentId())) return item;
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        throw new AssertionError("dead letter not found for content " + contentId);
    }

    private DefaultRocketMQListenerContainer startConsumer(String group, ContentVectorListener listener) throws Exception {
        var context = new StaticApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("vector-it", Map.of("rocketmq.name-server", NAMESERVER)));
        var container = new DefaultRocketMQListenerContainer();
        container.setName("vector-it-container");
        context.getBeanFactory().registerSingleton("vector-it-container", container);
        container.setApplicationContext(context);
        container.setRocketMQMessageListener(ContentVectorListener.class.getAnnotation(RocketMQMessageListener.class));
        container.setNameServer(NAMESERVER); container.setConsumerGroup(group); container.setTopic(MqConstants.TOPIC_CONTENT_VECTOR);
        container.setMessageConverter(new RocketMQMessageConverter().getMessageConverter());
        container.setRocketMQListener(listener);
        container.setDelayLevelWhenNextConsume(1);
        container.afterPropertiesSet();
        container.getConsumer().setVipChannelEnabled(false);
        container.start();
        return container;
    }

    private ContentDocMessage document(Long id, String title) {
        return ContentDocMessage.builder().contentId(id).type("video").authorId(9L).title(title)
                .description("简介").summary("摘要").statusCode("published").visibilityCode("public").build();
    }

    private boolean deadLetterContains(DefaultMQPullConsumer probe, String group, Long contentId) throws Exception {
        try {
            for (var queue : probe.fetchSubscribeMessageQueues("%DLQ%" + group)) {
                var messages = probe.pull(queue, "*", probe.minOffset(queue), 32).getMsgFoundList();
                if (messages != null) for (var message : messages) {
                    if (contentId.equals(new ObjectMapper().readValue(message.getBody(), ContentVectorMessage.class).contentId())) return true;
                }
            }
            return false;
        } catch (org.apache.rocketmq.client.exception.MQClientException absent) {
            return false;
        }
    }

    @FunctionalInterface private interface Check { boolean complete() throws Exception; }

    private void await(Check check, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.complete()) return;
            Thread.sleep(100);
        }
        fail(message);
    }
}
