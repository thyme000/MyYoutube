package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AudioProcessingPropertiesTest {
    @Test void bindsAndValidatesSchedulingAndTransportBudgetsTogether() {
        var env = new MockEnvironment().withProperty("my-youtube.audio-processing.scan-interval-ms", "3000")
                .withProperty("my-youtube.audio-processing.lease-seconds", "10")
                .withProperty("my-youtube.audio-processing.connect-timeout-ms", "100")
                .withProperty("my-youtube.audio-processing.read-timeout-ms", "400")
                .withProperty("my-youtube.audio-processing.round-budget-ms", "2000");
        var props = Binder.get(env).bind("my-youtube.audio-processing", Bindable.of(AudioProcessingProperties.class)).get();
        props.validate(); assertEquals(3000, props.getScanIntervalMs()); assertEquals(10, props.getLeaseSeconds());
        assertEquals(500, props.callBudgetMs()); assertEquals(1000, props.itemBudgetMs());
    }
    @Test void leaseMustCoverAllPerItemCallsAndDatabaseMargin() {
        var props = new AudioProcessingProperties(); props.setLeaseSeconds(4);
        assertThrows(IllegalStateException.class, props::validate);
        props.setLeaseSeconds(15); props.setReadTimeoutMs(10000);
        assertThrows(IllegalStateException.class, props::validate);
    }
    @Test void roundMustAdmitAnItemAndScanIntervalMustBePositive() {
        var props = new AudioProcessingProperties(); props.setRoundBudgetMs(3999);
        assertThrows(IllegalStateException.class, props::validate);
        props.setRoundBudgetMs(5000); props.setScanIntervalMs(0);
        assertThrows(IllegalStateException.class, props::validate);
        props.setScanIntervalMs(2000); props.setConnectTimeoutMs(0);
        assertThrows(IllegalStateException.class, props::validate);
    }
}
