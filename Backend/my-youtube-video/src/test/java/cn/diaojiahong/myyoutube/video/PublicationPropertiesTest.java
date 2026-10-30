package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.video.config.PublicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicationPropertiesTest {
    @Test
    void configuredTimingAndBudgetsAreBoundTogether() {
        MockEnvironment env = new MockEnvironment().withProperty("my-youtube.publication.scan-interval-ms", "3000")
                .withProperty("my-youtube.publication.batch-size", "20")
                .withProperty("my-youtube.publication.lease-seconds", "40")
                .withProperty("my-youtube.publication.waiting-seconds", "10")
                .withProperty("my-youtube.publication.deadline-minutes", "45")
                .withProperty("my-youtube.publication.retry-backoff-seconds", "7,20")
                .withProperty("my-youtube.publication.max-attempts", "2");
        PublicationProperties properties = Binder.get(env)
                .bind("my-youtube.publication", Bindable.of(PublicationProperties.class))
                .get();
        properties.validate();
        assertEquals(3000, properties.getScanIntervalMs());
        assertEquals(20, properties.getBatchSize());
        assertEquals(40, properties.getLeaseSeconds());
        assertEquals(10, properties.getWaitingSeconds());
        assertEquals(45, properties.getDeadlineMinutes());
        assertEquals(List.of(7, 20), properties.getRetryBackoffSeconds());
    }

    @Test
    void invalidRetryBudgetAndUnboundedTimingAreRejected() {
        var properties = new PublicationProperties();
        properties.setDeadlineMinutes(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setDeadlineMinutes(30);
        properties.setBatchSize(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setBatchSize(100);
        properties.setMaxAttempts(4);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setMaxAttempts(3);
        properties.setRetryBackoffSeconds(List.of(0, 5, 10));
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
