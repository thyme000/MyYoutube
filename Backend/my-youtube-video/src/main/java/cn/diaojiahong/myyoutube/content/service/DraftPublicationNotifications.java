package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.DraftPublicationMessage;
import cn.diaojiahong.myyoutube.video.api.enums.DraftSubmissionState;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class DraftPublicationNotifications {
    private final RocketMQTemplate mq;
    private final MeterRegistry metrics;

    public void afterCommit(MediaDraft draft) {
        if (draft == null || draft.getSubmissionState() != DraftSubmissionState.PENDING) return;
        var message = new DraftPublicationMessage(draft.getId(), draft.getSubmissionRevision());
        Runnable notify = () -> {
            try {
                mq.asyncSend(MqConstants.TOPIC_DRAFT_PUBLICATION, message, new SendCallback() {
                    @Override public void onSuccess(SendResult result) {
                        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) failed();
                    }
                    @Override public void onException(Throwable error) { failed(); }
                });
            } catch (Exception error) { failed(); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { notify.run(); }
            });
        else notify.run();
    }

    private void failed() {
        try { metrics.counter("publication.notification.failed").increment(); }
        catch (RuntimeException unavailable) { /* 可选通知和指标均不能阻止数据库调度。 */ }
    }
}
