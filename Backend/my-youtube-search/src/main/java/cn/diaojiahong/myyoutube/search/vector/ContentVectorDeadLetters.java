package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorCodec;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class ContentVectorDeadLetters {
    private static final String TOPIC = "%DLQ%" + MqConstants.GROUP_CONTENT_VECTOR;

    private static final CursorCodec<Reference> CURSOR = new CursorCodec<>(Reference.class, 2048, "无效游标");
    private final DefaultMQPullConsumer consumer;
    private final ObjectMapper mapper;
    private boolean started;

    record Entry(Reference reference, String messageId, Long contentId, long storedAt, int reconsumeTimes,
                 String targetVersion, String failureReason) {
    }

    record Page(List<Entry> entries, String nextCursor) {
    }

    @Autowired
    public ContentVectorDeadLetters(@Value("${rocketmq.name-server}") String nameServer, ObjectMapper mapper) {
        this(new DefaultMQPullConsumer("search_content_vector_dlq_reader"), mapper);
        consumer.setNamesrvAddr(nameServer);
        consumer.setInstanceName(UUID.randomUUID().toString());
        consumer.setVipChannelEnabled(false);
    }

    ContentVectorDeadLetters(DefaultMQPullConsumer consumer, ObjectMapper mapper) {
        this.consumer = consumer;
        this.mapper = mapper;
    }

    Page query(String cursor, Integer size) {
        if (size == null
                || size < 1
                || size > 50) throw new BizException(ErrorCode.INVALID_ARGUMENT, "size 必须在 1 到 50 之间");
        Reference start = cursor == null ? null : decode(cursor);
        try {
            List<MessageQueue> queues = queues();
            int first = start == null ? 0 : queues.indexOf(queue(start));
            if (first < 0) throw new BizException(ErrorCode.INVALID_ARGUMENT, "游标对应队列已不存在，请刷新列表");
            List<Entry> entries = new ArrayList<>();
            for (int i = first; i < queues.size(); i++) {
                MessageQueue queue = queues.get(i);
                long min = consumer.minOffset(queue), max = consumer.maxOffset(queue);
                long offset = start != null && i == first ? start.offset() : min;
                if (offset < min || offset > max) throw new BizException(ErrorCode.INVALID_ARGUMENT, "游标已过期，请刷新列表");
                while (offset < max) {
                    PullResult result = consumer.pull(queue, "*", offset, size - entries.size(), 3000);
                    if (result.getPullStatus() == PullStatus.OFFSET_ILLEGAL) {
                        throw new BizException(ErrorCode.INVALID_ARGUMENT, "游标已过期，请刷新列表");
                    }
                    if (result.getPullStatus() == PullStatus.NO_NEW_MSG) break;
                    if (result.getNextBeginOffset() <= offset) throw new IllegalStateException("死信读取位置未推进");
                    if (result.getMsgFoundList() != null) {
                        for (MessageExt message : result.getMsgFoundList()) entries.add(entry(queue, message));
                    }
                    offset = result.getNextBeginOffset();
                    if (entries.size() >= size) {
                        String next = offset < max
                                || i + 1 < queues.size() ? CURSOR.encode(reference(queue, offset)) : null;
                        return new Page(entries, next);
                    }
                }
            }
            return new Page(entries, null);
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("读取内容向量死信失败", error);
        }
    }

    Entry find(Reference reference) {
        validate(reference);
        try {
            MessageQueue queue = queue(reference);
            if (!queues().contains(queue)) return null;
            long offset = reference.offset();
            if (offset < consumer.minOffset(queue) || offset >= consumer.maxOffset(queue)) return null;
            PullResult result = consumer.pull(queue, "*", offset, 1, 3000);
            if (result.getPullStatus() != PullStatus.FOUND || result.getMsgFoundList() == null) return null;
            return result.getMsgFoundList().stream().filter(message -> message.getQueueOffset() == offset)
                    .findFirst().map(message -> entry(queue, message)).orElse(null);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("读取内容向量死信失败", error);
        }
    }

    private List<MessageQueue> queues() throws MQClientException {
        start();
        try {
            return consumer.fetchSubscribeMessageQueues(TOPIC).stream()
                    .sorted(Comparator.comparing(MessageQueue::getBrokerName).thenComparingInt(MessageQueue::getQueueId))
                    .toList();
        } catch (MQClientException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof MQClientException clientError
                        && clientError.getResponseCode() == ResponseCode.TOPIC_NOT_EXIST) {
                    return List.of();
                }
            }
            throw error;
        }
    }

    private synchronized void start() throws MQClientException {
        if (!started) {
            consumer.start();
            started = true;
        }
    }

    private Entry entry(MessageQueue queue, MessageExt message) {
        Long contentId = null;
        String targetVersion = null;
        String failureReason = null;
        try {
            ContentVectorMessage payload = mapper.readValue(message.getBody(), ContentVectorMessage.class);
            if (payload != null && payload.contentId() != null && payload.contentId() > 0
                    && (payload.targetVersion() == null || !payload.targetVersion().isBlank())) {
                contentId = payload.contentId();
                targetVersion = payload.targetVersion();
                failureReason = payload.failureReason();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // 损坏消息仍需展示，但禁止重投。
        }
        return new Entry(reference(queue, message.getQueueOffset()), message.getMsgId(), contentId,
                message.getStoreTimestamp(), message.getReconsumeTimes(), targetVersion, failureReason);
    }

    private Reference decode(String cursor) {
        if (cursor.isBlank()) throw new BizException(ErrorCode.INVALID_ARGUMENT, "无效游标");
        return CURSOR.decode(cursor, this::validate);
    }

    private void validate(Reference reference) {
        if (reference == null || reference.brokerName() == null || reference.brokerName().isBlank()
                || reference.brokerName().length() > 255 || reference.queueId() == null || reference.queueId() < 0
                || reference.offset() == null
                || reference.offset() < 0) throw new BizException(ErrorCode.INVALID_ARGUMENT, "无效死信定位信息");
    }

    private MessageQueue queue(Reference reference) {
        return new MessageQueue(TOPIC, reference.brokerName(), reference.queueId());
    }

    private Reference reference(MessageQueue queue, long offset) {
        return new Reference(queue.getBrokerName(), queue.getQueueId(), offset);
    }

    @PreDestroy
    public synchronized void close() {
        consumer.shutdown();
    }
}
