package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import cn.diaojiahong.myyoutube.mq.listener.ContentVectorListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorListenerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void vectorSwitchControlsConsumerRegistration(boolean enabled) {
        new ApplicationContextRunner().withUserConfiguration(ContentVectorListener.class)
                .withBean(ContentVectorService.class, () -> mock(ContentVectorService.class))
                .withPropertyValues("my-youtube.search.vector.enabled=" + enabled)
                .run(context -> assertEquals(enabled ? 1 : 0, context.getBeansOfType(ContentVectorListener.class).size()));
    }

    @Test void invalidMessageFailsInsteadOfAcknowledgingConsumption() {
        var service = mock(ContentVectorService.class);
        var listener = new ContentVectorListener(service);
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(null));
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(new ContentVectorMessage(null)));
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(new ContentVectorMessage(0L)));
        assertThrows(IllegalArgumentException.class, () -> listener.onMessage(new ContentVectorMessage(1L, " ", null)));
        verifyNoInteractions(service);
    }

    @Test void passesRecoveryTargetToTheExistingConsumerService() {
        var service = mock(ContentVectorService.class);
        new ContentVectorListener(service).onMessage(new ContentVectorMessage(1L, "model:2", "model failure"));
        verify(service).embedContent(1L, "model:2");
    }
}
