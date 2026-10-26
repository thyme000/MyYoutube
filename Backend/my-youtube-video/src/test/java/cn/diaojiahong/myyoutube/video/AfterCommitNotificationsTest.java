package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.content.service.AfterCommitNotifications;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AfterCommitNotificationsTest {
    final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final AfterCommitNotifications notifications = new AfterCommitNotifications(mq, metrics);

    @Test void transactionCommitSendsButRollbackDoesNot() {
        for (boolean commit : new boolean[]{false, true}) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                notifications.send("topic", "message", "notification.failed");
                verifyNoInteractions(mq);
                for (var hook : TransactionSynchronizationManager.getSynchronizations()) {
                    if (commit) hook.afterCommit();
                    hook.afterCompletion(commit ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK);
                }
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
        }
        verify(mq).asyncSend(eq("topic"), eq("message"), any(SendCallback.class));
    }

    @Test void callbackFailuresAndUnconfirmedResultsAreCounted() {
        doAnswer(call -> {
            SendCallback callback = call.getArgument(2);
            var result = new SendResult(); result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
            callback.onSuccess(result);
            return null;
        }).when(mq).asyncSend(anyString(), any(Object.class), any(SendCallback.class));
        notifications.send("topic", "message", "notification.failed");
        doAnswer(call -> {
            call.getArgument(2, SendCallback.class).onException(new IllegalStateException()); return null;
        }).when(mq).asyncSend(anyString(), any(Object.class), any(SendCallback.class));
        notifications.send("topic", "message", "notification.failed");
        assertEquals(2, metrics.counter("notification.failed").count());
    }
}
