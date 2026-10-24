package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;

public final class IsolatedDownloadWorker {
    public static void main(String[] args) throws Exception {
        try (var env = new IsolatedDownloadSupport()) {
            if (args[0].equals("crash")) {
                var row = env.tasks.getById(Long.valueOf(args[1]));
                var lock = env.tasks.artifactLock(row.getTaskKey()); lock.lock();
                env.tasks.updateByIdWithStatus(row.getId(),new TranscodeTask().setStatus(TranscodeTaskStatus.PROCESSING),TranscodeTaskStatus.PENDING);
                env.redis.getBucket(args[2]).set("locked");
                Thread.sleep(120000);
                lock.unlock();
                return;
            }
            var listener = env.listener(args[1]);
            var consumer = new DefaultMQPushConsumer(args[1]);
            consumer.setNamesrvAddr(IsolatedDownloadSupport.NAMESERVER);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.setConsumeThreadMin(1); consumer.setConsumeThreadMax(1);
            consumer.subscribe(MqConstants.TOPIC_DYNAMIC_TRANSCODE,"*");
            consumer.registerMessageListener((MessageListenerConcurrently)(messages,context) -> {
                try {
                    for(var message:messages) listener.onMessage(env.json.readValue(message.getBody(), TranscodeTaskMessage.class));
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                } catch(Exception e) { e.printStackTrace(); return ConsumeConcurrentlyStatus.RECONSUME_LATER; }
            });
            consumer.start(); env.redis.getBucket(args[1]).set("ready");
            try { Thread.sleep(180000); }
            finally { consumer.shutdown(); }
        }
    }
}
