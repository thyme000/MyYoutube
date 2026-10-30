package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationBusinessMetricsTest {
    final IUserNotificationService store = mock(IUserNotificationService.class);

    final PushFanoutPublisher push = mock(PushFanoutPublisher.class);

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final NotificationPublisher publisher = new NotificationPublisher(store, push, registry);

    final UserNotification notice = new UserNotification().setReceiverId(1L);

    @Test
    void falseSaveRetriesAndNeverSignalsOrReportsSuccess() {
        when(store.save(notice)).thenReturn(false);
        publisher.publish(notice);
        verify(store, times(3)).save(notice);
        verifyNoInteractions(push);
        assertEquals(
                1,
                registry.get(MetricNames.NOTIFICATION_DELIVERY_FAILURES).tag("stage", "store").counter().count());
        assertNull(registry.find(MetricNames.NOTIFICATION_DELIVERY_SUCCESSES).counter());
    }

    @Test
    void signalFailureDoesNotEraseDurableStoreSuccessOrRetryStore() {
        when(store.save(notice)).thenReturn(true);
        doThrow(new IllegalStateException()).when(push).pushSseSignal(1L, NotificationPublisher.NOTIFY_DIRTY_SIGNAL);
        publisher.publish(notice);
        verify(store).save(notice);
        assertEquals(
                1,
                registry.get(MetricNames.NOTIFICATION_DELIVERY_SUCCESSES).tag("stage", "store").counter().count());
        assertEquals(
                1,
                registry.get(MetricNames.NOTIFICATION_DELIVERY_FAILURES).tag("stage", "signal").counter().count());
        assertNull(registry.find(MetricNames.NOTIFICATION_DELIVERY_SUCCESSES).tag("stage", "signal").counter());
    }
}
