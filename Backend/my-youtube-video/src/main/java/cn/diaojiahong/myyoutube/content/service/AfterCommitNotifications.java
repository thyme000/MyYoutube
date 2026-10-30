package cn.diaojiahong.myyoutube.content.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 提交后尽力通知；可靠恢复由各业务的数据库补扫负责。 */
@Component
@RequiredArgsConstructor
public class AfterCommitNotifications {
    private final RocketMQTemplate mq;
    private final MeterRegistry metrics;

    public void send(String topic, Object message, String failureMetric) {
        Runnable notify = () -> {
            try {
                mq.asyncSend(topic, message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult result) {
                        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) failed(failureMetric);
                    }

                    @Override
                    public void onException(Throwable error) {
                        failed(failureMetric);
                    }
                });
            } catch (Exception error) {
                failed(failureMetric);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
        else notify.run();
    }

    private void failed(String name) {
        try {
            metrics.counter(name).increment();
        } catch (RuntimeException unavailable) {
            /* 可选通知和指标均不能阻止业务处理。 */
        }
    }
}
